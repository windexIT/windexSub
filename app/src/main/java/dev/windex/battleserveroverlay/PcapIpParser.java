package dev.windex.battleserveroverlay;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

final class PcapIpParser {
    private static final int PCAP_GLOBAL_HEADER_SIZE = 24;
    private static final int PCAP_PACKET_HEADER_SIZE = 16;

    private PcapIpParser() {}

    static PacketInfo parse(byte[] packet, int length) {
        if (length < 20) {
            return null;
        }

        if (looksLikePcapGlobalHeader(packet, length)) {
            if (length <= PCAP_GLOBAL_HEADER_SIZE) {
                return null;
            }
            return parseWithPcapRecordHeader(packet, PCAP_GLOBAL_HEADER_SIZE, length - PCAP_GLOBAL_HEADER_SIZE);
        }

        PacketInfo direct = parseIpPacket(packet, 0, length);
        if (direct != null) {
            return direct;
        }

        PacketInfo withRecordHeader = parseWithPcapRecordHeader(packet, 0, length);
        if (withRecordHeader != null) {
            return withRecordHeader;
        }

        if (length > PCAP_GLOBAL_HEADER_SIZE) {
            return parseIpPacket(packet, PCAP_GLOBAL_HEADER_SIZE, length - PCAP_GLOBAL_HEADER_SIZE);
        }

        return null;
    }

    private static boolean looksLikePcapGlobalHeader(byte[] packet, int length) {
        if (length < 4) {
            return false;
        }
        int magic = ((packet[0] & 0xff) << 24) | ((packet[1] & 0xff) << 16) | ((packet[2] & 0xff) << 8) | (packet[3] & 0xff);
        return magic == 0xa1b2c3d4 || magic == 0xd4c3b2a1 || magic == 0xa1b23c4d || magic == 0x4d3cb2a1;
    }

    private static PacketInfo parseWithPcapRecordHeader(byte[] packet, int offset, int length) {
        if (length <= PCAP_PACKET_HEADER_SIZE + 20) {
            return null;
        }

        int littleInclLen = readInt32(packet, offset + 8, true);
        if (littleInclLen > 0 && littleInclLen <= length - PCAP_PACKET_HEADER_SIZE) {
            PacketInfo parsed = parseIpPacket(packet, offset + PCAP_PACKET_HEADER_SIZE, littleInclLen);
            if (parsed != null) {
                return parsed;
            }
        }

        int bigInclLen = readInt32(packet, offset + 8, false);
        if (bigInclLen > 0 && bigInclLen <= length - PCAP_PACKET_HEADER_SIZE) {
            return parseIpPacket(packet, offset + PCAP_PACKET_HEADER_SIZE, bigInclLen);
        }

        return null;
    }

    private static PacketInfo parseIpPacket(byte[] data, int offset, int length) {
        if (length < 1 || offset < 0 || offset + length > data.length) {
            return null;
        }

        int version = (data[offset] >> 4) & 0x0f;
        if (version == 4) {
            return parseIpv4(data, offset, length);
        }
        if (version == 6) {
            return parseIpv6(data, offset, length);
        }
        return null;
    }

    private static PacketInfo parseIpv4(byte[] data, int offset, int length) {
        if (length < 20) {
            return null;
        }

        int headerLength = (data[offset] & 0x0f) * 4;
        if (headerLength < 20 || length < headerLength) {
            return null;
        }

        int protocol = data[offset + 9] & 0xff;
        byte[] source = Arrays.copyOfRange(data, offset + 12, offset + 16);
        byte[] destination = Arrays.copyOfRange(data, offset + 16, offset + 20);
        int sourcePort = -1;
        int destinationPort = -1;
        if ((protocol == 6 || protocol == 17) && length >= headerLength + 4) {
            sourcePort = readUInt16(data, offset + headerLength);
            destinationPort = readUInt16(data, offset + headerLength + 2);
        }

        return chooseRemote(source, destination, sourcePort, destinationPort, protocolName(protocol));
    }

    private static PacketInfo parseIpv6(byte[] data, int offset, int length) {
        if (length < 40) {
            return null;
        }

        int nextHeader = data[offset + 6] & 0xff;
        byte[] source = Arrays.copyOfRange(data, offset + 8, offset + 24);
        byte[] destination = Arrays.copyOfRange(data, offset + 24, offset + 40);
        int sourcePort = -1;
        int destinationPort = -1;
        if ((nextHeader == 6 || nextHeader == 17) && length >= 44) {
            sourcePort = readUInt16(data, offset + 40);
            destinationPort = readUInt16(data, offset + 42);
        }

        return chooseRemote(source, destination, sourcePort, destinationPort, protocolName(nextHeader));
    }

    private static PacketInfo chooseRemote(byte[] source, byte[] destination, int sourcePort, int destinationPort, String protocol) {
        boolean sourcePublic = isPublicAddress(source);
        boolean destinationPublic = isPublicAddress(destination);

        byte[] selectedAddress;
        int selectedPort;
        if (destinationPublic || (!sourcePublic && !isLoopbackOrPrivate(destination))) {
            selectedAddress = destination;
            selectedPort = destinationPort;
        } else if (sourcePublic) {
            selectedAddress = source;
            selectedPort = sourcePort;
        } else {
            selectedAddress = destination;
            selectedPort = destinationPort;
        }

        try {
            return new PacketInfo(InetAddress.getByAddress(selectedAddress).getHostAddress(), selectedPort, protocol);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static boolean isPublicAddress(byte[] address) {
        return !isLoopbackOrPrivate(address);
    }

    private static boolean isLoopbackOrPrivate(byte[] address) {
        if (address.length == 4) {
            int a = address[0] & 0xff;
            int b = address[1] & 0xff;
            return a == 0 || a == 10 || a == 127 || a >= 224
                    || (a == 100 && b >= 64 && b <= 127)
                    || (a == 169 && b == 254)
                    || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168);
        }

        if (address.length == 16) {
            int first = address[0] & 0xff;
            int second = address[1] & 0xff;
            return addressEquals(address, new byte[16])
                    || first == 0xff
                    || first == 0xfe && (second & 0xc0) == 0x80
                    || (first & 0xfe) == 0xfc
                    || (isIpv6Loopback(address));
        }

        return true;
    }

    private static boolean isIpv6Loopback(byte[] address) {
        for (int i = 0; i < 15; i++) {
            if (address[i] != 0) {
                return false;
            }
        }
        return address[15] == 1;
    }

    private static boolean addressEquals(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }

    private static int readUInt16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private static int readInt32(byte[] data, int offset, boolean littleEndian) {
        if (littleEndian) {
            return (data[offset] & 0xff)
                    | ((data[offset + 1] & 0xff) << 8)
                    | ((data[offset + 2] & 0xff) << 16)
                    | ((data[offset + 3] & 0xff) << 24);
        }
        return ((data[offset] & 0xff) << 24)
                | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xff);
    }

    private static String protocolName(int protocol) {
        if (protocol == 6) {
            return "TCP";
        }
        if (protocol == 17) {
            return "UDP";
        }
        return "IP";
    }
}
