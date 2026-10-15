package com.robot.common.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PageResultSnapshotTest {
    @Test
    void callerMutationCannotChangeAPreparedPageOrItsReplacement() {
        var rows = new ArrayList<>(List.of("original"));
        var page = PageResult.of(rows, 1, 1, 1);
        rows.clear();
        assertEquals(List.of("original"), page.getList());
        assertThrows(UnsupportedOperationException.class, () -> page.getList().clear());
        var replacement = new ArrayList<>(List.of("replacement"));
        page.setList(replacement);
        replacement.clear();
        assertEquals(List.of("replacement"), page.getList());
        page.setList(null);
        assertEquals(List.of(), page.getList());
    }
}
