package kr.ac.pusan.pickle.meta;

import java.util.List;

/** Contract: GET /meta/request-options response body. */
public record RequestOptionsResponse(List<String> allowedRootDomains, List<String> reservedSubdomains) {
}
