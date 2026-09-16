package kr.ac.pusan.pickle.networkpolicy;

import java.net.InetAddress;
import java.util.Arrays;

/** A literal, network-aligned IP prefix. Parsing never performs a DNS lookup. */
public final class CidrBlock {

    private final byte[] network;
    private final int prefixLength;
    private final String value;

    private CidrBlock(byte[] network, int prefixLength) {
        this.network = network.clone();
        this.prefixLength = prefixLength;
        this.value = format(network) + "/" + prefixLength;
    }

    public static CidrBlock parse(String value) {
        if (value == null || value.length() > 64) {
            throw invalid();
        }
        int slash = value.indexOf('/');
        if (slash <= 0 || slash != value.lastIndexOf('/')) {
            throw invalid();
        }
        byte[] address = literal(value.substring(0, slash));
        String bits = value.substring(slash + 1);
        if (!bits.matches("0|[1-9][0-9]{0,2}")) {
            throw invalid();
        }
        int prefix = Integer.parseInt(bits);
        if (prefix > address.length * 8) {
            throw invalid();
        }
        byte[] masked = mask(address, prefix);
        if (!Arrays.equals(address, masked)) {
            throw new IllegalArgumentException("CIDR에는 네트워크 주소를 입력해 주세요.");
        }
        return new CidrBlock(masked, prefix);
    }

    /** Converts an explicit host literal to /32 or /128 for the editor boundary. */
    public static CidrBlock host(String value) {
        byte[] address = literal(value);
        return new CidrBlock(address, address.length * 8);
    }

    public boolean ipv6() {
        return network.length == 16;
    }

    public boolean contains(String address) {
        byte[] candidate = literal(address);
        return candidate.length == network.length
                && Arrays.equals(network, mask(candidate, prefixLength));
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CidrBlock block && value.equals(block.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    private static byte[] literal(String value) {
        if (value == null || value.isEmpty() || value.length() > 45) {
            throw invalid();
        }
        if (!value.contains(":")) {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 4) {
                throw invalid();
            }
            byte[] result = new byte[4];
            for (int i = 0; i < parts.length; i++) {
                if (!parts[i].matches("0|[1-9][0-9]{0,2}")) {
                    throw invalid();
                }
                int octet = Integer.parseInt(parts[i]);
                if (octet > 255) {
                    throw invalid();
                }
                result[i] = (byte) octet;
            }
            return result;
        }
        // Brackets, scope IDs, whitespace and mapped IPv4 forms are not accepted.
        if (!value.matches("[0-9a-fA-F:]+")) {
            throw invalid();
        }
        try {
            byte[] result = InetAddress.ofLiteral(value).getAddress();
            if (result.length != 16) {
                throw invalid();
            }
            return result;
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static byte[] mask(byte[] address, int prefix) {
        byte[] result = address.clone();
        for (int i = 0; i < result.length; i++) {
            int remaining = prefix - i * 8;
            if (remaining <= 0) {
                result[i] = 0;
            } else if (remaining < 8) {
                result[i] &= (byte) (0xff << (8 - remaining));
            }
        }
        return result;
    }

    private static String format(byte[] address) {
        if (address.length == 4) {
            return (address[0] & 255) + "." + (address[1] & 255) + "."
                    + (address[2] & 255) + "." + (address[3] & 255);
        }
        int[] words = new int[8];
        for (int i = 0; i < words.length; i++) {
            words[i] = ((address[i * 2] & 255) << 8) | (address[i * 2 + 1] & 255);
        }
        int bestStart = -1;
        int bestLength = 1;
        for (int i = 0; i < words.length;) {
            if (words[i] != 0) {
                i++;
                continue;
            }
            int start = i;
            while (i < words.length && words[i] == 0) {
                i++;
            }
            if (i - start > bestLength) {
                bestStart = start;
                bestLength = i - start;
            }
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < words.length;) {
            if (i == bestStart) {
                result.append("::");
                i += bestLength;
            } else {
                if (!result.isEmpty() && result.charAt(result.length() - 1) != ':') {
                    result.append(':');
                }
                result.append(Integer.toHexString(words[i++]));
            }
        }
        return result.toString();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("올바른 IP 주소 또는 CIDR을 입력해 주세요.");
    }
}
