package kr.ac.pusan.pickle.common.web;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Contract page envelope for ever-growing lists (vm-requests/vms):
 * {@code content/page/size/totalElements/totalPages}.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    /** Wraps already-mapped content with the paging numbers of {@code page}. */
    public static <T> PageResponse<T> of(List<T> content, Page<?> page) {
        return new PageResponse<>(content, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
