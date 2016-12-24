package org.corfudb.runtime.view;

import org.corfudb.infrastructure.ManagementServer;
import org.corfudb.infrastructure.TestLayoutBuilder;
import org.corfudb.protocols.wireprotocol.CorfuMsgType;
import org.corfudb.runtime.clients.TestRule;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test to verify the Management Server functionality.
 * <p>
 * Created by zlokhandwala on 11/9/16.
 */
public class ManagementViewTest extends AbstractViewTest {

    /**
     * Boolean flag turned to true when the MANAGEMENT_FAILURE_DETECTED message
     * is sent by the Management client to its server.
     */
    private static final Semaphore failureDetected = new Semaphore(1,
            true);

    /**
     * Scenario with 2 nodes: SERVERS.PORT_0 and SERVERS.PORT_1.
     * We fail SERVERS.PORT_0 and then listen to intercept the message
     * sent by SERVERS.PORT_1's client to the server to handle the failure.
     *
     * @throws Exception
     */
    @Test
    public void invokeFailureHandler()
            throws Exception {
        addServer(SERVERS.PORT_0);
        addServer(SERVERS.PORT_1);
        Layout l = new TestLayoutBuilder()
                .setEpoch(1L)
                .addLayoutServer(SERVERS.PORT_0)
                .addLayoutServer(SERVERS.PORT_1)
                .addSequencer(SERVERS.PORT_0)
                .buildSegment()
                .buildStripe()
                .addLogUnit(SERVERS.PORT_0)
                .addLogUnit(SERVERS.PORT_1)
                .addToSegment()
                .addToLayout()
                .build();
        bootstrapAllServers(l);

        ManagementServer ms = getManagementServer(SERVERS.PORT_1);
        failureDetected.acquire();

        // Adding a rule on SERVERS.PORT_0 to drop all packets
        addServerRule(SERVERS.PORT_0, new TestRule().always().drop());
        getManagementServer(SERVERS.PORT_0).shutdown();

        // Adding a rule on SERVERS.PORT_1 to toggle the flag when it sends the
        // MANAGEMENT_FAILURE_DETECTED message.
        addClientRule(getManagementServer(SERVERS.PORT_1).getCorfuRuntime(),
                new TestRule().matches(corfuMsg -> {
            if (corfuMsg.getMsgType().equals(CorfuMsgType
                    .MANAGEMENT_FAILURE_DETECTED)) {
                failureDetected.release();
            }
            return true;
        }));

        assertThat(failureDetected.tryAcquire(PARAMETERS.TIMEOUT_NORMAL
                        .toNanos(),
                TimeUnit.NANOSECONDS)).isEqualTo(true);
    }


    /**
     * Tests the bootstrapping of the management server.
     *
     * @throws Exception
     */
    /*
    @Test
    public void handleBootstrap()
            throws Exception {
        // Since the servers are started as single nodes thus already bootstrapped.
        assertThatThrownBy(() -> getClient()
                .bootstrapManagement(TestLayoutBuilder.single(SERVERS.PORT_0))
                .get()).isInstanceOf(ExecutionException.class);
    }
    */

    /**
     * Tests the msg handler for failure detection.
     *
     * @throws Exception
     */
    /*
    @Test
    public void handleFailure()
            throws Exception {

        // Since the servers are started as single nodes thus already bootstrapped.
        Map map = new HashMap<String, Boolean>();
        map.put("Key", true);
        assertThat(getClient().handleFailure(map).get()).isEqualTo(true);
    }
    */
}