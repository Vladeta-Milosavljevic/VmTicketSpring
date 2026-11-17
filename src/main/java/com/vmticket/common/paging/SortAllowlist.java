package com.vmticket.common.paging;

import com.vmticket.common.error.BadRequestException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

public final class SortAllowlist {

    private SortAllowlist() {
    }

    public static void requireAllowed(Pageable pageable, Set<String> allowed) {
        if (pageable == null || pageable.getSort().isUnsorted()) {
            return;
        }
        for (Sort.Order order : pageable.getSort()) {
            if (!allowed.contains(order.getProperty())) {
                throw new BadRequestException("Unsupported sort property");
            }
        }
    }
}
