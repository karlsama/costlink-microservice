package com.costlink.reimbursement.util;

import java.util.Map;
import java.util.Set;

public class ReimbursementStatusMachine {

    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
        "DRAFT",    Set.of("PENDING"),
        "PENDING",  Set.of("APPROVED", "REJECTED", "DRAFT"),
        "REJECTED", Set.of("DRAFT"),
        "APPROVED", Set.of("PAID"),
        "PAID",     Set.of()
    );

    public static boolean canTransition(String from, String to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }
}
