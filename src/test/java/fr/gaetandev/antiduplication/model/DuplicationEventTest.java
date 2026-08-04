/*
 * AntiDuplication: a plugin for Minecraft servers.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package fr.gaetandev.antiduplication.model;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DuplicationEventTest {

    private static final String UUID_TAG = "TEST_12345678";
    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static TrackedItem copyAt(final int slot) {
        return new TrackedItem.Builder()
                .uuid(UUID_TAG)
                .material(Material.DIAMOND_SWORD)
                .location(ItemLocation.ofPlayer(
                        ItemLocation.Kind.PLAYER_INVENTORY, PLAYER_ID, "TestPlayer", slot))
                .build();
    }

    private static List<TrackedItem> copiesAt(final int... slots) {
        final List<TrackedItem> copies = new ArrayList<>();
        for (final int slot : slots) {
            copies.add(copyAt(slot));
        }
        return copies;
    }

    @Test
    @DisplayName("Everything but the retained copy is scheduled for removal")
    void testItemsToRemove() {
        final List<TrackedItem> copies = copiesAt(1, 2, 3);
        final DuplicationEvent event = new DuplicationEvent.Builder()
                .duplicatedUuid(UUID_TAG)
                .duplicatedItems(copies)
                .retainedItem(copies.get(1))
                .build();

        assertThat(event.getDuplicateCount()).isEqualTo(3);
        assertThat(event.getRetainedItem()).isEqualTo(copies.get(1));
        assertThat(event.getItemsToRemove()).containsExactly(copies.get(0), copies.get(2));
    }

    @Test
    @DisplayName("Without an explicit choice the first copy is kept")
    void testDefaultRetainedItem() {
        final List<TrackedItem> copies = copiesAt(4, 5);
        final DuplicationEvent event = new DuplicationEvent.Builder()
                .duplicatedUuid(UUID_TAG)
                .duplicatedItems(copies)
                .build();

        assertThat(event.getRetainedItem()).isEqualTo(copies.get(0));
        assertThat(event.getItemsToRemove()).containsExactly(copies.get(1));
    }

    @Test
    @DisplayName("Severity grows with the number of copies")
    void testSeverityFromCopyCount() {
        assertThat(eventWithCopies(2).getSeverity()).isEqualTo(DuplicationEvent.Severity.LOW);
        assertThat(eventWithCopies(3).getSeverity()).isEqualTo(DuplicationEvent.Severity.MEDIUM);
        assertThat(eventWithCopies(5).getSeverity()).isEqualTo(DuplicationEvent.Severity.HIGH);
        assertThat(eventWithCopies(10).getSeverity()).isEqualTo(DuplicationEvent.Severity.CRITICAL);
    }

    @Test
    @DisplayName("An explicit severity is not overridden")
    void testExplicitSeverityWins() {
        final DuplicationEvent event = new DuplicationEvent.Builder()
                .duplicatedUuid(UUID_TAG)
                .duplicatedItems(copiesAt(1, 2))
                .severity(DuplicationEvent.Severity.CRITICAL)
                .build();

        assertThat(event.getSeverity()).isEqualTo(DuplicationEvent.Severity.CRITICAL);
    }

    @Test
    @DisplayName("An event needs at least two copies and a retained copy from the list")
    void testValidation() {
        assertThrows(NullPointerException.class, () -> new DuplicationEvent.Builder()
                .duplicatedItems(copiesAt(1, 2))
                .build());

        assertThrows(NullPointerException.class, () -> new DuplicationEvent.Builder()
                .duplicatedUuid(UUID_TAG)
                .build());

        assertThrows(IllegalArgumentException.class, () -> new DuplicationEvent.Builder()
                .duplicatedUuid(UUID_TAG)
                .duplicatedItems(Collections.singletonList(copyAt(1)))
                .build());

        assertThrows(IllegalArgumentException.class, () -> new DuplicationEvent.Builder()
                .duplicatedUuid(UUID_TAG)
                .duplicatedItems(copiesAt(1, 2))
                .retainedItem(copyAt(9))
                .build());
    }

    @Test
    @DisplayName("The copy list is defensively copied and unmodifiable")
    void testImmutability() {
        final List<TrackedItem> copies = new ArrayList<>(copiesAt(1, 2));
        final DuplicationEvent event = new DuplicationEvent.Builder()
                .duplicatedUuid(UUID_TAG)
                .duplicatedItems(copies)
                .build();

        copies.add(copyAt(3));

        assertThat(event.getDuplicateCount()).isEqualTo(2);
        assertThrows(UnsupportedOperationException.class, () -> event.getDuplicatedItems().add(copyAt(4)));
    }

    @Test
    @DisplayName("The detailed description marks which copy survives")
    void testDetailedDescription() {
        final List<TrackedItem> copies = copiesAt(1, 2);
        final DuplicationEvent event = new DuplicationEvent.Builder()
                .duplicatedUuid(UUID_TAG)
                .duplicatedItems(copies)
                .retainedItem(copies.get(0))
                .additionalInfo("All copies held by TestPlayer.")
                .build();

        assertThat(event.getDetailedDescription())
                .contains("[kept] Player TestPlayer (Player inventory, slot 1)")
                .contains("[removed] Player TestPlayer (Player inventory, slot 2)")
                .contains("All copies held by TestPlayer.");
        assertThat(event.getSummary()).contains(UUID_TAG).contains("2 copies");
    }

    private static DuplicationEvent eventWithCopies(final int count) {
        final int[] slots = new int[count];
        for (int i = 0; i < count; i++) {
            slots[i] = i;
        }
        return new DuplicationEvent.Builder()
                .duplicatedUuid(UUID_TAG)
                .duplicatedItems(copiesAt(slots))
                .build();
    }
}
