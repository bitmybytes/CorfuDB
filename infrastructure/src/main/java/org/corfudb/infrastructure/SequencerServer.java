package org.corfudb.infrastructure;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.collect.ImmutableMap;
import io.netty.channel.ChannelHandlerContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.corfudb.protocols.wireprotocol.*;
import org.corfudb.protocols.wireprotocol.CorfuMsgType;
import org.corfudb.util.Utils;

import java.lang.invoke.MethodHandles;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.corfudb.infrastructure.ServerContext.NON_LOG_ADDR_MAGIC;

/**
 * This server implements the sequencer functionality of Corfu.
 * <p>
 * It currently supports a single operation, which is a incoming request:
 * <p>
 * TOKEN_REQ - Request the next token.
 * <p>
 * Created by mwei on 12/8/15.
 */
@Slf4j
public class SequencerServer extends AbstractServer {

    /**
     * key-name for storing {@link SequencerServer} state in {@link ServerContext::getDataStore()}.
     */
    private static final String PREFIX_SEQUENCER = "SEQUENCER";
    private static final String KEY_SEQUENCER = "CURRENT";

    /**
     * Inherit from CorfuServer a server context
     */
    private final ServerContext serverContext;

    /**
     * Our options
     */
    private final Map<String, Object> opts;

    /**
     * The sequencer maintains information about log and streams:
     *
     *  - {@link SequencerServer::globalLogTail}: global log tail
     *  - {@link SequencerServer::streamTailMap}: a map of per-stream tail
     *  - {@link SequencerServer::streamTailToGlobalTailMap}: map from stream-tails to global-log tails. used for backpointers.
     *  - {@link SequencerServer::conflictToGlobalTailCache}: the {@link SequencerServer::maxConflictCacheSize} latest conflict keys and their latest commit (global-log) position
     *
     * Every append to the log updates the information in these maps.
     */
    @Getter
    private final AtomicLong globalLogTail = new AtomicLong(0L);
    private final ConcurrentHashMap<UUID, Long> streamTailMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> streamTailToGlobalTailMap = new ConcurrentHashMap<>();
    private final long maxConflictCacheSize = 10_000;
    private final Cache<Object, Long> conflictToGlobalTailCache = Caffeine.newBuilder()
            .maximumSize(maxConflictCacheSize)
                .build();

    /**
     * A sequencer needs a lease to serve a certain number of tokens.
     * The lease starting index is persisted.
     * A lease is good for (@Link #SequencerServer::leaseLength) number of tokens.
     *
     * A lease is renewed when we reach leaseRenew tokens away from the limit.
     *
     * TODO: these parameters should probably be configurable from somewhere
     */
    @Getter
    private final long leaseLength = 100_000;
    private final long leaseRenewalNotice = 10_000; // renew when token crosses leaseLength - leaseRenewalNotice threshold

    /** Handler for this server */
    @Getter
    private CorfuMsgHandler handler = new CorfuMsgHandler()
            .generateHandlers(MethodHandles.lookup(), this);

    public SequencerServer(ServerContext serverContext) {
        this.serverContext = serverContext;
        this.opts = serverContext.getServerConfig();

        long initialToken = Utils.parseLong(opts.get("--initial-token"));
        if (initialToken == NON_LOG_ADDR_MAGIC) {
            getInitalLease();
        } else {
            renewLease(initialToken);
            globalLogTail.set(initialToken);
        }
    }

    /**
     * Returns true if the txn commits.
     * If the request submits a timestamp (a global offset) that is less than one of the
     * global offsets of a stream specified in the request, then abort; otherwise commit.
     *
     * @param timestamp Read timestamp of the txn; in order to commit, no writes may be made past this
     *                  (global) timestamp on any streams touched by the txn.
     * @param streams   Read set of the txn.
     */
    public boolean txnResolution(long timestamp, Set<UUID> streams) {
        log.trace("txn resolution, timestamp: {}, streams: {}", timestamp, streams);

        AtomicBoolean commit = new AtomicBoolean(true);
        for (UUID id : streams) {
            if (!commit.get())
                break;


            streamTailToGlobalTailMap.compute(id, (k, v) -> {
                if (v == null) {
                    return null;
                } else {
                    if (v > timestamp) {
                        log.debug("Rejecting request due to {} > {} on stream {}", v, timestamp, id);
                        commit.set(false);
                    }
                }
                return v;
            });
        }
        return commit.get();
    }

    public void returnLatestOffsets(CorfuPayloadMsg<TokenRequest> msg,
                                    ChannelHandlerContext ctx, IServerRouter r) {
        TokenRequest req = msg.getPayload();

        long maxStreamGlobalTails = -1L;

        // Collect the latest local offset for every stream in the request.
        ImmutableMap.Builder<UUID, Long> responseStreamTails = ImmutableMap.builder();

        for (UUID id : req.getStreams()) {
            streamTailMap.compute(id, (k, v) -> {
                if (v == null) {
                    responseStreamTails.put(k, -1L);
                    return null;
                }
                responseStreamTails.put(k, v);
                return v;
            });
            // Compute the latest global offset across all streams.
            Long lastIssued = streamTailToGlobalTailMap.get(id);
            maxStreamGlobalTails = Math.max(maxStreamGlobalTails, lastIssued == null ? Long.MIN_VALUE : lastIssued);
        }

        // If no streams are specified in the request, this value returns the last global token issued.
        long responseGlobalTail = (req.getStreams().size() == 0) ? globalLogTail.get() - 1 : maxStreamGlobalTails;
        r.sendResponse(ctx, msg, CorfuMsgType.TOKEN_RES.payloadMsg(
                new TokenResponse(responseGlobalTail, Collections.emptyMap(), responseStreamTails.build())));
    }

    /**
     * Service an incoming token request.
     */
    @ServerHandler(type=CorfuMsgType.TOKEN_REQ)
    public synchronized void tokenRequest(CorfuPayloadMsg<TokenRequest> msg,
                                          ChannelHandlerContext ctx, IServerRouter r) {
        TokenRequest req = msg.getPayload();
        log.trace("req txn reso: {}", req.getTxnResolution());

        // if requested number of tokens is zero, it is just a query of current tail(s)
        if (req.getNumTokens() == 0) {
            returnLatestOffsets(msg, ctx, r);
            return;
        }

        // check if need to renew sequencer lease
        long leaseRenew = getCurrentLease() + leaseLength;
        if (globalLogTail.get() >= (leaseRenew - leaseRenewalNotice))
            renewLease(leaseRenew);

        // if no streams, simply allocate a position at the tail of the global log
        if (req.getStreams() == null) {
            r.sendResponse(ctx, msg, CorfuMsgType.TOKEN_RES.payloadMsg(
                    new TokenResponse(globalLogTail.getAndAdd(req.getNumTokens()), Collections.emptyMap(), Collections.emptyMap())));
            return;
        }

        // If the request is a transaction resolution request, then check if it should abort.
        if (req.getTxnResolution()) {
            if (!txnResolution(req.getReadTimestamp(), req.getReadSet())) {
                // If the txn aborts, then DO NOT hand out a token.
                r.sendResponse(ctx, msg, CorfuMsgType.TOKEN_RES.payloadMsg(
                        new TokenResponse(-1L, Collections.emptyMap(), Collections.emptyMap())));
                return;
            }
        }

        long currentTail = globalLogTail.getAndAdd(req.getNumTokens());

        // If the txn can commit, or if the request is for a non-txn entry, then proceed normally to
        // hand out local stream offsets.
        ImmutableMap.Builder<UUID, Long> backPointerMap = ImmutableMap.builder();
        ImmutableMap.Builder<UUID, Long> requestStreamTokens = ImmutableMap.builder();
        for (UUID id : req.getStreams()) {
            streamTailToGlobalTailMap.compute(id, (k, v) -> {
                if (v == null) {
                    backPointerMap.put(k, -1L);
                    return currentTail + req.getNumTokens() - 1;
                }
                backPointerMap.put(k, v);
                return Math.max(currentTail + req.getNumTokens() - 1, v);
            });
            /*
             * Action table for (overwrite, replexOverwrite) pairs:
             * overwrite | replexOverwrite | Action
             *   F              F            Hand out tokens as requested
             *   F              T            There was an overwrite in the local stream layer, so allocate
             *                               a new global token AND increment local stream offsets. The
             *                               action should be identical to the (F,F) case.
             *   T              F            There was an overwrite in the global log layer, so ONLY
             *                               allocate a new global token, and DO NOT increment local
             *                               stream offsets.
             *   T              T            This should never happen, because the Replex write protocol
             *                               terminates immediately if it encounters a global log overwrite.
             */
            /* TODO: In the (F,T) case, hole-filling (or some other mechanism, perhaps the same writer),
             * needs to mark the hanging entry in the global log with a false commit bit.
             */
            if (msg.getPayload().getReplexOverwrite() ||
                    !msg.getPayload().getOverwrite()) {
                // Collect the stream offsets for this token request.
                streamTailMap.compute(id, (k, v) -> {
                    if (v == null) {
                        requestStreamTokens.put(k, req.getNumTokens() - 1L);
                        return req.getNumTokens() - 1L;
                    }
                    requestStreamTokens.put(k, v + req.getNumTokens());
                    return v + req.getNumTokens();
                });
            }
        }

        r.sendResponse(ctx, msg, CorfuMsgType.TOKEN_RES.payloadMsg(
                new TokenResponse(currentTail,
                        backPointerMap.build(),
                        requestStreamTokens.build())));
    }

    /**
     * obtain the initial lease (a log tail).
     * for now, this works only with a local file.
     * TODO in the future, a sequencer needs to obtain the lease from the layout service
     */
    private void getInitalLease() {

        // check for existing previous lease
        Long leaseTail = serverContext.getDataStore()
                .get(Long.class, PREFIX_SEQUENCER, KEY_SEQUENCER);

        if (leaseTail != null) {
            // if a previous lease exists, go past it to teh next lease segment
            renewLease(leaseTail + leaseLength);
            globalLogTail.set(leaseTail + leaseLength);
            // todo: we need to update the conflictCache to reflect the lack of information up to the current tail
        } else {
            // otherwise, grab a lease from the start of the log
            renewLease(0L);
            globalLogTail.set(0L);
        }

    }

    /**
     * extend the current lease to a new tail
     * @param leaseStart the new lease starting point
     */
    private void renewLease(long leaseStart) {
        serverContext.getDataStore()
                .put(Long.class, PREFIX_SEQUENCER, KEY_SEQUENCER, leaseStart);
    }

    /**
     * query the current lease
     * @return the lease's starting point
     */
    private long getCurrentLease() {
        return serverContext.getDataStore()
                .get(Long.class, PREFIX_SEQUENCER, KEY_SEQUENCER);
    }
}
