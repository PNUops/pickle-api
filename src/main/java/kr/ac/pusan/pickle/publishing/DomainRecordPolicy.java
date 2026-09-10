package kr.ac.pusan.pickle.publishing;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.config.PublishingProperties;
import kr.ac.pusan.pickle.publishing.dns.DnsRecordType;
import kr.ac.pusan.pickle.publishing.dns.TxtValues;
import kr.ac.pusan.pickle.settings.SettingsService;
import org.springframework.stereotype.Component;

/**
 * What a domain's owner may put in the platform's zone.
 *
 * <p>The name under it is ours, so what it points at is our problem even
 * though the content is not. Three families of rule, each here for a reason
 * the others do not cover:</p>
 *
 * <ul>
 *   <li><strong>Nothing may point back at us.</strong> The operator's standing
 *       decision is that this platform does not relay traffic for targets it
 *       does not serve, and a record aimed at our own ingress is exactly that
 *       relay arranged from the outside. It also collides with the zone
 *       reconciler, which treats a single-label A holding the proxy address as
 *       a record the platform itself wrote.</li>
 *   <li><strong>Nothing may name an address that is not a destination.</strong>
 *       A private, loopback or link-local address in a public zone is either a
 *       mistake or a way to publish our internal addressing, and the campus
 *       ranges are how a university name would be lent to somebody else's
 *       machine on the same network.</li>
 *   <li><strong>Nothing may borrow the parent's mail reputation.</strong> No MX
 *       is offered, which is not the same as being safe: a permissive SPF at a
 *       name under a university domain makes mail from that name pass a
 *       receiving check.</li>
 * </ul>
 */
@Component
public class DomainRecordPolicy {

    /** One label, or several joined by dots. Underscore leads a label for ACME and the like. */
    private static final Pattern NAME =
            Pattern.compile("^[a-z0-9_]([a-z0-9_-]*[a-z0-9])?(\\.[a-z0-9_]([a-z0-9_-]*[a-z0-9])?)*$");

    /** RFC 1035 label length, applied per label rather than to the whole name. */
    private static final int MAX_LABEL = 63;

    static final int MAX_SETS_PER_DOMAIN = 20;
    static final int MAX_VALUES_PER_SET = 10;
    static final int MIN_TTL = 60;
    static final int MAX_TTL = 86400;

    /**
     * Addresses this platform answers on. Held as CIDR text and compared
     * numerically: the campus block is the important one, because a name under
     * a university root aimed at another machine on that network is the shape
     * a phishing page takes.
     */
    private static final List<String> OURS_V4 = List.of(
            "164.125.0.0/16",   // campus, this platform's ingress among them
            "52.78.104.182/32"  // the off-premises relay
    );

    /** Ranges that are not destinations on the public internet. */
    private static final List<String> NOT_A_DESTINATION_V4 = List.of(
            "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
            "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.168.0.0/16",
            "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4",
            "240.0.0.0/4");

    /**
     * Names this institution answers on. A CNAME is the same lending of a
     * university name that the campus address range is refused for, arranged
     * one level up: the target is never resolved here, so an address rule
     * cannot see it, and a name under a platform root pointing at a campus
     * host is the shape a phishing page takes whether it got there by address
     * or by name. Held as suffixes because that is all a policy that refuses
     * to resolve anything can compare.
     */
    private static final List<String> CAMPUS_SUFFIXES = List.of(
            "pusan.ac.kr",
            "pnu.app",
            "pcl.kr",
            "pnuops.com");

    /** Mail policy lives at the parent; a child must not speak for it. */
    private static final List<String> MAIL_POLICY_PREFIXES =
            List.of("v=spf1", "v=dmarc1", "v=stsv1", "v=dkim1");

    private final PublishingProperties properties;
    private final SettingsService settingsService;

    public DomainRecordPolicy(PublishingProperties properties, SettingsService settingsService) {
        this.properties = properties;
        this.settingsService = settingsService;
    }

    /** One set as the caller asked for it, before it is compared with what exists. */
    public record DesiredSet(String name, DnsRecordType type, List<String> rrdatas, int ttl) {

        /**
         * The set as it is stored and pushed. Every rule below reads a value
         * stripped and, where case carries no meaning, folded; storing the raw
         * string instead would leave validation and storage disagreeing about
         * what the value is, and it is the stored one the zone receives. A
         * leading space passes every check here and then fails at the provider
         * for as long as the row exists.
         *
         * <p>TXT keeps its case and its inner spacing — both are data there,
         * and the only thing safe to take off is the surrounding whitespace a
         * form adds.</p>
         */
        public DesiredSet normalized() {
            List<String> values = rrdatas.stream()
                    .map(value -> type == DnsRecordType.TXT
                            ? value.strip()
                            : value.strip().toLowerCase(Locale.ROOT))
                    .toList();
            return new DesiredSet(name.strip().toLowerCase(Locale.ROOT), type, values, ttl);
        }
    }

    /**
     * Validates the whole desired set for one domain, because three of these
     * rules are about the set as a group: how many sets there are, that a CNAME
     * shares its name with nothing, and that no two sets claim the same name
     * and type.
     */
    public void validate(List<DesiredSet> sets, String field, List<FieldValidationError> errors) {
        if (sets.size() > MAX_SETS_PER_DOMAIN) {
            errors.add(new FieldValidationError(field,
                    "레코드는 도메인당 최대 " + MAX_SETS_PER_DOMAIN + "개까지 등록할 수 있습니다."));
        }
        Set<String> seen = new HashSet<>();
        Set<String> cnamed = new HashSet<>();
        Set<String> named = new HashSet<>();
        for (int i = 0; i < sets.size(); i++) {
            DesiredSet set = sets.get(i);
            String at = field + "[" + i + "]";
            validateName(set.name(), at, errors);
            validateTtl(set.ttl(), at, errors);
            validateValues(set, at, errors);
            if (!seen.add(set.name() + "/" + set.type())) {
                errors.add(new FieldValidationError(at,
                        "같은 이름과 종류의 레코드를 두 번 지정했습니다."));
            }
            if (set.type() == DnsRecordType.CNAME) {
                cnamed.add(set.name());
            }
            named.add(set.name());
        }
        // RFC 1034: a name carrying a CNAME carries nothing else. A rule about
        // the other rows at the same name, which is why the database cannot
        // hold it and this can.
        for (DesiredSet set : sets) {
            if (set.type() != DnsRecordType.CNAME && cnamed.contains(set.name())) {
                errors.add(new FieldValidationError(field,
                        "'" + display(set.name()) + "'에 CNAME과 다른 종류를 함께 둘 수 없습니다."));
                break;
            }
        }
        named.clear();
    }

    private void validateName(String name, String at, List<FieldValidationError> errors) {
        if (name == null) {
            errors.add(new FieldValidationError(at, "레코드 이름이 필요합니다."));
            return;
        }
        if (name.isEmpty()) {
            return; // the domain's own name
        }
        if (!NAME.matcher(name).matches()) {
            errors.add(new FieldValidationError(at,
                    "레코드 이름은 소문자와 숫자, 하이픈, 밑줄만 쓸 수 있고 점으로 끝날 수 없습니다."));
            return;
        }
        for (String label : name.split("\\.")) {
            if (label.length() > MAX_LABEL) {
                errors.add(new FieldValidationError(at, "레코드 이름의 각 라벨은 63자를 넘을 수 없습니다."));
                return;
            }
        }
    }

    private void validateTtl(int ttl, String at, List<FieldValidationError> errors) {
        if (ttl < MIN_TTL || ttl > MAX_TTL) {
            errors.add(new FieldValidationError(at,
                    "TTL은 " + MIN_TTL + "초에서 " + MAX_TTL + "초 사이여야 합니다."));
        }
    }

    private void validateValues(DesiredSet set, String at, List<FieldValidationError> errors) {
        List<String> values = set.rrdatas();
        if (values == null || values.isEmpty()) {
            errors.add(new FieldValidationError(at, "레코드 값이 하나 이상 필요합니다."));
            return;
        }
        if (values.size() > MAX_VALUES_PER_SET) {
            errors.add(new FieldValidationError(at,
                    "한 레코드에는 값을 최대 " + MAX_VALUES_PER_SET + "개까지 둘 수 있습니다."));
        }
        if (set.type() == DnsRecordType.CNAME && values.size() > 1) {
            errors.add(new FieldValidationError(at, "CNAME은 값을 하나만 가질 수 있습니다."));
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                errors.add(new FieldValidationError(at, "빈 레코드 값은 둘 수 없습니다."));
                continue;
            }
            switch (set.type()) {
                case A -> validateAddress(value, true, at, errors);
                case AAAA -> validateAddress(value, false, at, errors);
                case CNAME -> validateTarget(value, at, errors);
                case TXT -> validateText(value, at, errors);
                default -> errors.add(new FieldValidationError(at, "지원하지 않는 레코드 종류입니다."));
            }
        }
    }

    private void validateAddress(String value, boolean wantV4, String at,
            List<FieldValidationError> errors) {
        InetAddress address;
        try {
            // Literal only. Resolving here would let a record's value decide
            // what this server looks up, and would answer for a name rather
            // than for the address that is actually being published.
            address = InetAddress.ofLiteral(value.strip());
        } catch (IllegalArgumentException e) {
            errors.add(new FieldValidationError(at, "'" + value + "'은(는) 올바른 IP 주소가 아닙니다."));
            return;
        }
        if (wantV4 && !(address instanceof Inet4Address)) {
            errors.add(new FieldValidationError(at, "A 레코드에는 IPv4 주소를 적어야 합니다."));
            return;
        }
        if (!wantV4 && !(address instanceof Inet6Address)) {
            // An IPv4-mapped literal parses as IPv4 here, and that is the point:
            // written into a AAAA record it would publish a v4 address as though
            // it were v6, which resolvers hand to clients that cannot use it.
            errors.add(new FieldValidationError(at,
                    "AAAA 레코드에는 IPv6 주소를 적어야 합니다. IPv4 주소를 IPv6 형식으로 감싼 값은 쓸 수 없습니다."));
            return;
        }
        if (isOurs(address)) {
            errors.add(new FieldValidationError(at,
                    "이 플랫폼이 응답하는 주소는 지정할 수 없습니다. 도메인 리소스는 플랫폼 밖 대상을 위한 것입니다."));
            return;
        }
        if (!isDestination(address)) {
            errors.add(new FieldValidationError(at,
                    "'" + value + "'은(는) 공개 인터넷에서 도달할 수 있는 주소가 아닙니다."));
        }
    }

    private void validateTarget(String value, String at, List<FieldValidationError> errors) {
        String target = value.strip().toLowerCase(Locale.ROOT);
        String bare = target.endsWith(".") ? target.substring(0, target.length() - 1) : target;
        if (bare.isEmpty() || !bare.contains(".") || !NAME.matcher(bare).matches()) {
            errors.add(new FieldValidationError(at, "'" + value + "'은(는) 올바른 대상 이름이 아닙니다."));
            return;
        }
        // A CNAME into a platform root would tie this name to one the platform
        // can reclaim, and a reclaimed name reissued to somebody else would
        // carry this one with it.
        for (String root : settingsService.stringList(SettingsService.ALLOWED_ROOT_DOMAINS)) {
            String lower = root.toLowerCase(Locale.ROOT);
            if (bare.equals(lower) || bare.endsWith("." + lower)) {
                errors.add(new FieldValidationError(at,
                        "플랫폼이 관리하는 이름을 대상으로 지정할 수 없습니다."));
                return;
            }
        }
        // And the same refusal the address rules make, at the level a name
        // reaches. Without this the whole "nothing may point back at us"
        // family is one CNAME away from being bypassed.
        for (String suffix : CAMPUS_SUFFIXES) {
            if (bare.equals(suffix) || bare.endsWith("." + suffix)) {
                errors.add(new FieldValidationError(at,
                        "교내 도메인을 대상으로 지정할 수 없습니다."));
                return;
            }
        }
    }

    private void validateText(String value, String at, List<FieldValidationError> errors) {
        if (TxtValues.octets(value) > TxtValues.MAX_OCTETS) {
            errors.add(new FieldValidationError(at,
                    "TXT 값은 " + TxtValues.MAX_OCTETS + "바이트를 넘을 수 없습니다."));
        }
        String lower = value.strip().toLowerCase(Locale.ROOT);
        for (String prefix : MAIL_POLICY_PREFIXES) {
            if (lower.startsWith(prefix)) {
                errors.add(new FieldValidationError(at,
                        "메일 정책 레코드는 등록할 수 없습니다. 상위 도메인의 발신 평판에 영향을 줍니다."));
                return;
            }
        }
    }

    private boolean isOurs(InetAddress address) {
        if (address instanceof Inet4Address v4) {
            long value = toLong(v4);
            for (String cidr : OURS_V4) {
                if (inCidr(value, cidr)) {
                    return true;
                }
            }
            return sameAsProxy(v4);
        }
        return false;
    }

    private boolean sameAsProxy(Inet4Address v4) {
        try {
            return InetAddress.ofLiteral(properties.proxyPublicIp()).equals(v4);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isDestination(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        if (address instanceof Inet4Address v4) {
            long value = toLong(v4);
            for (String cidr : NOT_A_DESTINATION_V4) {
                if (inCidr(value, cidr)) {
                    return false;
                }
            }
            return true;
        }
        byte[] bytes = address.getAddress();
        // Unique local (fc00::/7) and the documentation prefix (2001:db8::/32).
        if ((bytes[0] & 0xFE) == 0xFC) {
            return false;
        }
        return !((bytes[0] & 0xFF) == 0x20 && (bytes[1] & 0xFF) == 0x01
                && (bytes[2] & 0xFF) == 0x0D && (bytes[3] & 0xFF) == 0xB8);
    }

    private static long toLong(Inet4Address address) {
        byte[] bytes = address.getAddress();
        long value = 0;
        for (byte b : bytes) {
            value = (value << 8) | (b & 0xFF);
        }
        return value;
    }

    private static boolean inCidr(long address, String cidr) {
        int slash = cidr.indexOf('/');
        int prefix = Integer.parseInt(cidr.substring(slash + 1));
        long base = 0;
        for (String part : cidr.substring(0, slash).split("\\.")) {
            base = (base << 8) | Integer.parseInt(part);
        }
        long mask = prefix == 0 ? 0 : (~0L << (32 - prefix)) & 0xFFFFFFFFL;
        return (address & mask) == (base & mask);
    }

    private static String display(String name) {
        return name.isEmpty() ? "@" : name;
    }

    /** The desired sets in a stable order, so a diff reads the same twice. */
    public static List<DesiredSet> ordered(List<DesiredSet> sets) {
        List<DesiredSet> copy = new ArrayList<>(sets);
        copy.sort((a, b) -> {
            int byName = a.name().compareTo(b.name());
            return byName != 0 ? byName : a.type().compareTo(b.type());
        });
        return List.copyOf(copy);
    }
}
