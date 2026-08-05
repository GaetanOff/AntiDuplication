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

package fr.gaetandev.antiduplication.core;

import fr.gaetandev.antiduplication.model.ItemLocation;
import fr.gaetandev.antiduplication.model.TrackedItem;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StackDivisionTrackerTest {

    private static final String UUID_TAG = "TEST_12345678";
    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static StackDivisionTracker tracker(final long toleranceMillis, final int maxDivisions) {
        return new StackDivisionTracker(() -> toleranceMillis, () -> maxDivisions);
    }

    private static List<TrackedItem> pieces(final int... amounts) {
        final List<TrackedItem> copies = new ArrayList<>();
        for (int slot = 0; slot < amounts.length; slot++) {
            copies.add(new TrackedItem.Builder()
                    .uuid(UUID_TAG)
                    .material(Material.DIAMOND)
                    .amount(amounts[slot])
                    .location(ItemLocation.ofPlayer(
                            ItemLocation.Kind.PLAYER_INVENTORY, PLAYER_ID, "Alice", slot))
                    .build());
        }
        return copies;
    }

    @Test
    @DisplayName("An unknown UUID is never a legitimate division")
    void testUnknownUuid() {
        assertThat(tracker(30_000L, 4).isLegitimate(UUID_TAG, pieces(32, 32))).isFalse();
    }

    @Test
    @DisplayName("Pieces that add up to the split stack are accepted")
    void testWithinBudget() {
        final StackDivisionTracker tracker = tracker(30_000L, 4);
        tracker.register(UUID_TAG, 64);

        assertThat(tracker.isLegitimate(UUID_TAG, pieces(32, 32))).isTrue();
        assertThat(tracker.isLegitimate(UUID_TAG, pieces(16, 16, 32))).isTrue();
    }

    @Test
    @DisplayName("Pieces adding up to more than the split stack are a duplication")
    void testOverBudget() {
        final StackDivisionTracker tracker = tracker(30_000L, 4);
        tracker.register(UUID_TAG, 64);

        assertThat(tracker.isLegitimate(UUID_TAG, pieces(64, 64))).isFalse();
        assertThat(tracker.isLegitimate(UUID_TAG, pieces(32, 33))).isFalse();
    }

    @Test
    @DisplayName("More pieces than allowed is a duplication whatever the total")
    void testTooManyPieces() {
        final StackDivisionTracker tracker = tracker(30_000L, 2);
        tracker.register(UUID_TAG, 64);

        assertThat(tracker.isLegitimate(UUID_TAG, pieces(32, 32))).isTrue();
        assertThat(tracker.isLegitimate(UUID_TAG, pieces(16, 16, 16))).isFalse();
    }

    @Test
    @DisplayName("A record past its tolerance window no longer authorises anything")
    void testExpiry() {
        final StackDivisionTracker tracker = tracker(0L, 4);
        tracker.register(UUID_TAG, 64);

        assertThat(tracker.isLegitimate(UUID_TAG, pieces(32, 32))).isFalse();
        // Consulting an expired record also drops it.
        assertThat(tracker.size()).isZero();
    }

    @Test
    @DisplayName("Re-registering keeps the most permissive total")
    void testRegisterKeepsHighestTotal() {
        final StackDivisionTracker tracker = tracker(30_000L, 4);
        tracker.register(UUID_TAG, 64);
        tracker.register(UUID_TAG, 16);

        assertThat(tracker.isLegitimate(UUID_TAG, pieces(32, 32))).isTrue();
    }

    @Test
    @DisplayName("Records can be dropped explicitly and in bulk")
    void testForgetAndEvict() {
        final StackDivisionTracker tracker = tracker(30_000L, 4);
        tracker.register(UUID_TAG, 64);
        tracker.register("TEST_87654321", 64);
        assertThat(tracker.size()).isEqualTo(2);

        tracker.forget(UUID_TAG);
        assertThat(tracker.size()).isEqualTo(1);

        assertThat(tracker(30_000L, 4).evictExpired()).isZero();

        final StackDivisionTracker expiring = tracker(0L, 4);
        expiring.register(UUID_TAG, 64);
        assertThat(expiring.evictExpired()).isEqualTo(1);
    }

    @Test
    @DisplayName("A null UUID is ignored rather than stored")
    void testNullUuid() {
        final StackDivisionTracker tracker = tracker(30_000L, 4);
        tracker.register(null, 64);

        assertThat(tracker.size()).isZero();
        assertThat(tracker.isLegitimate(UUID_TAG, Collections.emptyList())).isFalse();
    }
}
