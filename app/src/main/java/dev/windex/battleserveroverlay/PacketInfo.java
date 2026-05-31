package dev.windex.battleserveroverlay;

final class PacketInfo {
    final String remoteAddress;
    final int remotePort;
    final String protocol;

    PacketInfo(String remoteAddress, int remotePort, String protocol) {
        this.remoteAddress = remoteAddress;
        this.remotePort = remotePort;
        this.protocol = protocol;
    }
}
