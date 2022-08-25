/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.p2p.gossip;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.UUID;

import org.lealone.db.Constants;
import org.lealone.net.NetNode;
import org.lealone.p2p.gossip.protocol.IVersionedSerializer;
import org.lealone.p2p.server.MessagingService;
import org.lealone.p2p.util.P2pUtils;

/**
 * This abstraction represents the state associated with a particular node which an
 * application wants to make available to the rest of the nodes in the cluster.
 * Whenever a piece of state needs to be disseminated to the rest of cluster wrap
 * the state in an instance of <i>ApplicationState</i> and add it to the Gossiper.
 * <p></p>
 * e.g. if we want to disseminate load information for node A do the following:
 * <p></p>
 * ApplicationState loadState = new ApplicationState(<string representation of load>);
 * Gossiper.instance.addApplicationState("LOAD STATE", loadState);
 */
public class VersionedValue implements Comparable<VersionedValue> {

    public static final IVersionedSerializer<VersionedValue> serializer = new VersionedValueSerializer();

    // this must be a char that cannot be present in any token
    public final static char DELIMITER = ',';
    public final static String DELIMITER_STR = new String(new char[] { DELIMITER });

    // values for ApplicationState.STATUS
    public final static String STATUS_NORMAL = "NORMAL";
    public final static String STATUS_LEAVING = "LEAVING";
    public final static String STATUS_LEFT = "LEFT";

    public final static String REMOVING_TOKEN = "removing";
    public final static String REMOVED_TOKEN = "removed";

    public final static String HIBERNATE = "hibernate";

    // values for ApplicationState.REMOVAL_COORDINATOR
    public final static String REMOVAL_COORDINATOR = "REMOVER";

    public final int version;
    public final String value;

    private VersionedValue(String value, int version) {
        assert value != null;
        // blindly interning everything is somewhat suboptimal -- lots of VersionedValues are unique --
        // but harmless, and interning the non-unique ones saves significant memory. (Unfortunately,
        // we don't really have enough information here in VersionedValue to tell the probably-unique
        // values apart.) See Cassandra-6410.
        this.value = value.intern();
        this.version = version;
    }

    private VersionedValue(String value) {
        this(value, VersionGenerator.getNextVersion());
    }

    @Override
    public int compareTo(VersionedValue value) {
        return this.version - value.version;
    }

    @Override
    public String toString() {
        return "Value(" + value + "," + version + ")";
    }

    private static String versionString(String... args) {
        return P2pUtils.join(args, VersionedValue.DELIMITER);
    }

    public static class VersionedValueFactory {

        public VersionedValueFactory() {
        }

        public VersionedValue cloneWithHigherVersion(VersionedValue value) {
            return new VersionedValue(value.value);
        }

        public VersionedValue normal(String hostId) {
            return new VersionedValue(versionString(VersionedValue.STATUS_NORMAL, hostId));
        }

        public VersionedValue load(double load) {
            return new VersionedValue(String.valueOf(load));
        }

        public VersionedValue schema(UUID newVersion) {
            return new VersionedValue(newVersion.toString());
        }

        public VersionedValue leaving(String hostId) {
            return new VersionedValue(versionString(VersionedValue.STATUS_LEAVING, hostId));
        }

        public VersionedValue left(String hostId, long expireTime) {
            return new VersionedValue(
                    versionString(VersionedValue.STATUS_LEFT, hostId, Long.toString(expireTime)));
        }

        public VersionedValue hostId(String hostId) {
            return new VersionedValue(hostId);
        }

        public VersionedValue removingNonlocal(UUID hostId) {
            return new VersionedValue(versionString(VersionedValue.REMOVING_TOKEN, hostId.toString()));
        }

        public VersionedValue removedNonlocal(UUID hostId, long expireTime) {
            return new VersionedValue(versionString(VersionedValue.REMOVED_TOKEN, hostId.toString(),
                    Long.toString(expireTime)));
        }

        public VersionedValue removalCoordinator(UUID hostId) {
            return new VersionedValue(
                    versionString(VersionedValue.REMOVAL_COORDINATOR, hostId.toString()));
        }

        public VersionedValue hibernate(boolean value) {
            return new VersionedValue(VersionedValue.HIBERNATE + VersionedValue.DELIMITER + value);
        }

        public VersionedValue datacenter(String dcId) {
            return new VersionedValue(dcId);
        }

        public VersionedValue rack(String rackId) {
            return new VersionedValue(rackId);
        }

        public VersionedValue node(NetNode node) {
            return new VersionedValue(node.getHostAndPort());
        }

        public VersionedValue releaseVersion() {
            return new VersionedValue(Constants.RELEASE_VERSION);
        }

        public VersionedValue networkVersion() {
            return new VersionedValue(String.valueOf(MessagingService.CURRENT_VERSION));
        }

        public VersionedValue internalIP(String private_ip) {
            return new VersionedValue(private_ip);
        }

        public VersionedValue severity(double value) {
            return new VersionedValue(String.valueOf(value));
        }
    }

    private static class VersionedValueSerializer implements IVersionedSerializer<VersionedValue> {
        @Override
        public void serialize(VersionedValue value, DataOutput out, int version) throws IOException {
            out.writeUTF(outValue(value, version));
            out.writeInt(value.version);
        }

        private String outValue(VersionedValue value, int version) {
            return value.value;
        }

        @Override
        public VersionedValue deserialize(DataInput in, int version) throws IOException {
            String value = in.readUTF();
            int valVersion = in.readInt();
            return new VersionedValue(value, valVersion);
        }
    }
}
