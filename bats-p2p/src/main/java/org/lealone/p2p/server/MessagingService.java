/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.p2p.server;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.lealone.common.exceptions.DbException;
import org.lealone.common.logging.Logger;
import org.lealone.common.logging.LoggerFactory;
import org.lealone.common.util.ExpiringMap;
import org.lealone.common.util.Pair;
import org.lealone.db.async.AsyncPeriodicTask;
import org.lealone.db.async.AsyncTask;
import org.lealone.db.async.AsyncTaskHandlerFactory;
import org.lealone.net.AsyncConnection;
import org.lealone.net.AsyncConnectionManager;
import org.lealone.net.NetFactory;
import org.lealone.net.NetFactoryManager;
import org.lealone.net.NetNode;
import org.lealone.net.WritableChannel;
import org.lealone.p2p.concurrent.ScheduledExecutors;
import org.lealone.p2p.config.ConfigDescriptor;
import org.lealone.p2p.gossip.Gossiper;
import org.lealone.p2p.gossip.protocol.EchoPacket;
import org.lealone.p2p.gossip.protocol.GossipDigestAck;
import org.lealone.p2p.gossip.protocol.GossipDigestAck2;
import org.lealone.p2p.gossip.protocol.GossipDigestSyn;
import org.lealone.p2p.gossip.protocol.GossipResponse;
import org.lealone.p2p.gossip.protocol.GossipShutdown;
import org.lealone.p2p.gossip.protocol.P2pPacketOut;
import org.lealone.p2p.locator.ILatencySubscriber;
import org.lealone.p2p.util.P2pUtils;
import org.lealone.server.protocol.PacketDecoders;
import org.lealone.server.protocol.PacketType;

@SuppressWarnings({ "rawtypes" })
public final class MessagingService implements MessagingServiceMBean, AsyncConnectionManager {

    private static final Logger logger = LoggerFactory.getLogger(MessagingService.class);

    public static final int VERSION_10 = 1;
    public static final int CURRENT_VERSION = VERSION_10;

    public static final String FAILURE_CALLBACK_PARAM = "CAL_BAC";
    public static final String FAILURE_RESPONSE_PARAM = "FAIL";
    public static final byte[] ONE_BYTE = new byte[1];

    /**
     * we preface every message with this number so the recipient can validate the sender is sane
     */
    public static final int PROTOCOL_MAGIC = 0xCA552DFA;

    public static void validateMagic(int magic) throws IOException {
        if (magic != PROTOCOL_MAGIC)
            throw new IOException("invalid protocol header");
    }

    /**
     * Packets it's okay to drop if the request has been queued longer than the request timeout.
     * These all correspond to client requests or something triggered by them; 
     * we don't want to drop internal messages like bootstrap.
     */
    public static final EnumSet<PacketType> DROPPABLE_PACKETS = EnumSet
            .of(PacketType.P2P_REQUEST_RESPONSE);

    private static final int LOG_DROPPED_INTERVAL_IN_MS = 5000;

    private static final AtomicInteger idGen = new AtomicInteger(0);

    private static int nextId() {
        return idGen.incrementAndGet();
    }

    /* This records all the results mapped by message Id */
    private final ExpiringMap<Integer, CallbackInfo> callbacks;

    // total dropped message counts for server lifetime
    private final Map<PacketType, DroppedMessageMetrics> droppedMessages = new EnumMap<>(
            PacketType.class);
    // dropped count when last requested for the Recent api. high concurrency isn't necessary here.
    private final Map<PacketType, Integer> lastDroppedInternal = new EnumMap<>(PacketType.class);

    private final List<ILatencySubscriber> subscribers = new ArrayList<>();

    // protocol versions of the other nodes in the cluster
    private final ConcurrentMap<NetNode, Integer> versions = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, P2pConnection> connections = new ConcurrentHashMap<>();

    private static class MSHandle {
        public static final MessagingService instance = new MessagingService();
    }

    public static MessagingService instance() {
        return MSHandle.instance;
    }

    private MessagingService() {
        for (PacketType verb : DROPPABLE_PACKETS) {
            // droppedMessages.put(verb, new DroppedMessageMetrics(verb));
            lastDroppedInternal.put(verb, 0);
        }

        AsyncPeriodicTask logDropped = new AsyncPeriodicTask() {
            @Override
            public void run() {
                logDroppedMessages();
            }
        };
        ScheduledExecutors.scheduledTasks.scheduleWithFixedDelay(logDropped, LOG_DROPPED_INTERVAL_IN_MS,
                LOG_DROPPED_INTERVAL_IN_MS, TimeUnit.MILLISECONDS);

        Function<Pair<Integer, ExpiringMap.CacheableObject<CallbackInfo>>, ?> timeoutReporter = //
                new Function<Pair<Integer, ExpiringMap.CacheableObject<CallbackInfo>>, Object>() {
                    @Override
                    public Object apply(Pair<Integer, ExpiringMap.CacheableObject<CallbackInfo>> pair) {
                        final CallbackInfo expiredCallbackInfo = pair.right.value;
                        maybeAddLatency(expiredCallbackInfo.callback, expiredCallbackInfo.target,
                                pair.right.timeout);
                        // ConnectionMetrics.totalTimeouts.mark();
                        getConnection(expiredCallbackInfo.target).incrementTimeout();
                        if (expiredCallbackInfo.isFailureCallback()) {
                            AsyncTask task = new AsyncTask() {
                                @Override
                                public void run() {
                                    ((IAsyncCallbackWithFailure) expiredCallbackInfo.callback)
                                            .onFailure(expiredCallbackInfo.target);
                                }

                                @Override
                                public int getPriority() {
                                    return MIN_PRIORITY;
                                }
                            };
                            AsyncTaskHandlerFactory.getAsyncTaskHandler().handle(task);
                        }
                        return null;
                    }
                };

        callbacks = new ExpiringMap<Integer, CallbackInfo>(AsyncTaskHandlerFactory.getAsyncTaskHandler(),
                ConfigDescriptor.getRpcTimeout(), timeoutReporter);

        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try {
            mbs.registerMBean(this, new ObjectName(P2pUtils.getJmxObjectName("MessagingService")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        registerPacketDecoders();
    }

    private void registerPacketDecoders() {
        PacketDecoders.register(PacketType.P2P_ECHO, EchoPacket.decoder);
        PacketDecoders.register(PacketType.P2P_GOSSIP_DIGEST_SYN, GossipDigestSyn.decoder);
        PacketDecoders.register(PacketType.P2P_GOSSIP_DIGEST_ACK, GossipDigestAck.decoder);
        PacketDecoders.register(PacketType.P2P_GOSSIP_DIGEST_ACK2, GossipDigestAck2.decoder);
        PacketDecoders.register(PacketType.P2P_GOSSIP_SHUTDOWN, GossipShutdown.decoder);
        PacketDecoders.register(PacketType.P2P_REQUEST_RESPONSE, GossipResponse.decoder);
    }

    public void start() {
        callbacks.reset(); // hack to allow tests to stop/restart MS
    }

    /**
     * Wait for callbacks and don't allow any more to be created (since they could require writing hints)
     */
    public void shutdown() {
        logger.info("Waiting for messaging service to quiesce");
    }

    public void incrementDroppedMessages(PacketType verb) {
        assert DROPPABLE_PACKETS.contains(verb) : "Verb " + verb + " should not legally be dropped";
        droppedMessages.get(verb).dropped.incrementAndGet();
    }

    private void logDroppedMessages() {
        for (Map.Entry<PacketType, DroppedMessageMetrics> entry : droppedMessages.entrySet()) {
            int dropped = (int) entry.getValue().dropped.get();
            PacketType verb = entry.getKey();
            int recent = dropped - lastDroppedInternal.get(verb);
            if (recent > 0) {
                logger.info("{} {} messages dropped in last {}ms",
                        new Object[] { recent, verb, LOG_DROPPED_INTERVAL_IN_MS });
                lastDroppedInternal.put(verb, dropped);
            }
        }
    }

    /**
     * Track latency information for the dynamic snitch
     * 
     * @param cb      the callback associated with this message -- 
     *                this lets us know if it's a message type we're interested in
     * @param address the host that replied to the message
     * @param latency
     */
    public void maybeAddLatency(IAsyncCallback cb, NetNode address, long latency) {
        if (cb.isLatencyForSnitch())
            addLatency(address, latency);
    }

    public void addLatency(NetNode address, long latency) {
        for (ILatencySubscriber subscriber : subscribers)
            subscriber.receiveTiming(address, latency);
    }

    public int sendRR(P2pPacketOut message, NetNode to, IAsyncCallback cb) {
        return sendRR(message, to, cb, message.getTimeout(), false);
    }

    public int sendRRWithFailure(P2pPacketOut message, NetNode to, IAsyncCallbackWithFailure cb) {
        return sendRR(message, to, cb, message.getTimeout(), true);
    }

    /**
     * Send a non-mutation message to a given node. This method specifies a callback
     * which is invoked with the actual response.
     *
     * @param message message to be sent.
     * @param to      node to which the message needs to be sent
     * @param cb      callback interface which is used to pass the responses or
     *                suggest that a timeout occurred to the invoker of the send().
     * @param timeout the timeout used for expiration
     * @return an reference to message id used to match with the result
     */
    public int sendRR(P2pPacketOut message, NetNode to, IAsyncCallback cb, long timeout,
            boolean failureCallback) {
        int id = addCallback(message, to, cb, timeout, failureCallback);
        sendOneWay(failureCallback ? message.withParameter(FAILURE_CALLBACK_PARAM, ONE_BYTE) : message,
                id, to);
        return id;
    }

    private int addCallback(P2pPacketOut message, NetNode to, IAsyncCallback cb, long timeout,
            boolean failureCallback) {
        int messageId = nextId();
        CallbackInfo previous = callbacks.put(messageId, new CallbackInfo(to, cb, failureCallback),
                timeout);
        assert previous == null : String.format("Callback already exists for id %d! (%s)", messageId,
                previous);
        return messageId;
    }

    public void sendOneWay(P2pPacketOut message, NetNode to) {
        sendOneWay(message, nextId(), to);
    }

    public void sendReply(P2pPacketOut message, int id, NetNode to) {
        sendOneWay(message, id, to);
    }

    /**
     * Send a message to a given node. This method adheres to the fire and forget
     * style messaging.
     *
     * @param message messages to be sent.
     * @param to      node to which the message needs to be sent
     */
    public void sendOneWay(P2pPacketOut message, int id, NetNode to) {
        if (logger.isTraceEnabled()) {
            if (to.equals(ConfigDescriptor.getLocalNode()))
                logger.trace("Message-to-self {} going over MessagingService", message);
            else
                logger.trace("{} sending {} to {}@{}", ConfigDescriptor.getLocalNode(),
                        message.packet.getType(), id, to);
        }
        P2pConnection conn = getConnection(to);
        if (conn != null)
            conn.enqueue(message, id);
    }

    public P2pConnection getConnection(NetNode remoteNode) {
        remoteNode = ClusterMetaData.getPreferredIP(remoteNode);
        String remoteHostAndPort = remoteNode.getHostAndPort();
        P2pConnection conn = connections.get(remoteHostAndPort);
        if (conn == null) {
            synchronized (connections) {
                conn = connections.get(remoteHostAndPort);
                if (conn != null)
                    return conn;

                Map<String, String> config = P2pServer.instance.getConfig();
                NetFactory factory = NetFactoryManager.getFactory(config);
                try {
                    conn = (P2pConnection) factory.getNetClient()
                            .createConnection(config, remoteNode, this).get();
                    String localHostAndPort = ConfigDescriptor.getLocalNode().getHostAndPort();
                    conn.initTransfer(remoteNode, localHostAndPort);
                    // connections.put(remoteHostAndPort, conn); //调用initTransfer成功后已经加到connections
                } catch (Exception e) {
                    String msg = "Failed to connect " + remoteNode;
                    // TODO 是否不应该立刻移除节点
                    if (Gossiper.instance.tryRemoveNode(remoteNode)) {
                        logger.error(msg, e);
                        throw DbException.convert(e);
                    } else {
                        logger.warn(msg);
                    }
                }
            }
        }
        return conn;
    }

    public NetNode getConnectionNode(NetNode to) {
        return getConnection(to).node();
    }

    public void reconnect(NetNode old, NetNode to) {
        getConnection(old).reset(to);
    }

    /**
     * called from gossiper when it notices a node is not responding.
     */
    public void convict(NetNode ep) {
        if (logger.isDebugEnabled())
            logger.debug("Resetting pool for {}", ep);
        getConnection(ep).reset();
    }

    public void register(ILatencySubscriber subcriber) {
        subscribers.add(subcriber);
    }

    public CallbackInfo getRegisteredCallback(int messageId) {
        return callbacks.get(messageId, true);
    }

    public CallbackInfo removeRegisteredCallback(int messageId) {
        return callbacks.remove(messageId, true);
    }

    /**
     * @return System.nanoTime() when callback was created.
     */
    public long getRegisteredCallbackAge(int messageId) {
        return callbacks.getAge(messageId);
    }

    public void setVersion(NetNode node, int version) {
        if (logger.isDebugEnabled())
            logger.debug("Setting version {} for {}", version, node);

        versions.put(node, version);
    }

    public void removeVersion(NetNode node) {
        if (logger.isDebugEnabled())
            logger.debug("Removing version for {}", node);
        versions.remove(node);
    }

    public int getVersion(NetNode node) {
        Integer v = versions.get(node);
        if (v == null) {
            // we don't know the version. assume current. we'll know soon enough if that was incorrect.
            if (logger.isTraceEnabled())
                logger.trace("Assuming current protocol version for {}", node);
            return MessagingService.CURRENT_VERSION;
        } else
            return Math.min(v, MessagingService.CURRENT_VERSION);
    }

    public boolean knowsVersion(NetNode node) {
        return versions.containsKey(node);
    }

    public void addConnection(P2pConnection conn) {
        P2pConnection oldConn = connections.put(conn.getHostAndPort(), conn);
        if (oldConn != null) {
            oldConn.close();
        }
    }

    private void removeConnection(String hostId) {
        P2pConnection oldConn = connections.remove(hostId);
        if (oldConn != null) {
            oldConn.close();
        }
    }

    public void removeConnection(NetNode ep) {
        removeConnection(ep.getHostAndPort());
    }

    public void removeConnection(P2pConnection conn) {
        removeConnection(conn.getHostAndPort());
    }

    @Override
    public void removeConnection(AsyncConnection conn) {
        removeConnection((P2pConnection) conn);
    }

    @Override
    public P2pConnection createConnection(WritableChannel writableChannel, boolean isServer) {
        // 此时还不能把创建的连接放到connections中，
        // 如果是服务器端的连接，需要等到执行完P2pConnection.readInitPacket后才加入connections
        // 如果是客户端(也就是对等端)的连接，需要等到执行完P2pConnection.writeInitPacket后才加入connections
        return new P2pConnection(writableChannel, isServer);
    }

    // --------------以下是MessagingServiceMBean的API实现-------------

    @Override
    public int getVersion(String node) throws UnknownHostException {
        return getVersion(NetNode.getByName(node));
    }

    @Override
    public Map<String, Integer> getResponsePendingTasks() {
        Map<String, Integer> pendingTasks = new HashMap<>(connections.size());
        for (P2pConnection conn : connections.values())
            pendingTasks.put(conn.node().getHostAddress(), conn.getPendingMessages());
        return pendingTasks;
    }

    @Override
    public Map<String, Long> getResponseCompletedTasks() {
        Map<String, Long> completedTasks = new HashMap<>(connections.size());
        for (P2pConnection conn : connections.values())
            completedTasks.put(conn.node().getHostAddress(), conn.getCompletedMesssages());
        return completedTasks;
    }

    @Override
    public Map<String, Integer> getDroppedMessages() {
        Map<String, Integer> map = new HashMap<>(droppedMessages.size());
        for (Map.Entry<PacketType, DroppedMessageMetrics> entry : droppedMessages.entrySet())
            map.put(entry.getKey().toString(), (int) entry.getValue().dropped.get());
        return map;
    }

    @Override
    public long getTotalTimeouts() {
        return 0; // ConnectionMetrics.totalTimeouts.count();
    }

    @Override
    public Map<String, Long> getTimeoutsPerHost() {
        Map<String, Long> result = new HashMap<>(connections.size());
        for (P2pConnection conn : connections.values()) {
            result.put(conn.node().getHostAddress(), conn.getTimeouts());
        }
        return result;
    }

    private static class DroppedMessageMetrics {
        AtomicLong dropped = new AtomicLong();
    }
}
