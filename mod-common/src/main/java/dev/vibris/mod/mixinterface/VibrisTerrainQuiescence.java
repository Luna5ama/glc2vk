package dev.vibris.mod.mixinterface;

import net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.Objects;

/**
 * Exposes the pinned terrain renderer's complete CPU-side work state to Vibris.
 */
public interface VibrisTerrainQuiescence {
	TerrainSnapshot iris$captureTerrainSnapshot();

	record TerrainSnapshot(
		boolean quiescent,
		List<RenderListState> regularRenderLists,
		List<RenderListState> shadowRenderLists,
		List<ShadowTaskState> shadowPendingTasks,
		boolean shadowNeedsRenderListUpdate,
		List<GlobalEntitySectionState> globalEntitySections,
		String mismatch
	) {
		public TerrainSnapshot {
			regularRenderLists = List.copyOf(regularRenderLists);
			shadowRenderLists = List.copyOf(shadowRenderLists);
			shadowPendingTasks = List.copyOf(shadowPendingTasks);
			globalEntitySections = List.copyOf(globalEntitySections);
			Objects.requireNonNull(mismatch, "mismatch");
		}
	}

	/**
	 * Records both forward and reverse SortedRenderLists traversals. Forward entries also retain the
	 * sprite and entity iteration orders; reverse entries retain the translucent geometry draw order.
	 */
	record RenderListState(
		boolean reverse,
		Object identity,
		Object regionIdentity,
		List<SectionState> geometrySections,
		List<SectionState> spriteSections,
		List<SectionState> entitySections
	) {
		public RenderListState {
			Objects.requireNonNull(identity, "identity");
			Objects.requireNonNull(regionIdentity, "regionIdentity");
			geometrySections = List.copyOf(geometrySections);
			spriteSections = List.copyOf(spriteSections);
			entitySections = List.copyOf(entitySections);
		}

		@Override
		public boolean equals(Object other) {
			return this == other || other instanceof RenderListState state && reverse == state.reverse &&
				identity == state.identity && regionIdentity == state.regionIdentity &&
				geometrySections.equals(state.geometrySections) && spriteSections.equals(state.spriteSections) &&
				entitySections.equals(state.entitySections);
		}

		@Override
		public int hashCode() {
			int result = Boolean.hashCode(reverse);
			result = 31 * result + System.identityHashCode(identity);
			result = 31 * result + System.identityHashCode(regionIdentity);
			result = 31 * result + geometrySections.hashCode();
			result = 31 * result + spriteSections.hashCode();
			return 31 * result + entitySections.hashCode();
		}
	}

	record SectionState(
		long position,
		int flags,
		long visibilityData,
		int lastUploadFrame,
		int pendingUpdateType,
		long pendingUpdateSince,
		Object identity
	) {
		@Override
		public boolean equals(Object other) {
			return this == other || other instanceof SectionState state && position == state.position &&
				flags == state.flags && visibilityData == state.visibilityData &&
				lastUploadFrame == state.lastUploadFrame && pendingUpdateType == state.pendingUpdateType &&
				pendingUpdateSince == state.pendingUpdateSince && identity == state.identity;
		}

		@Override
		public int hashCode() {
			int result = Long.hashCode(position);
			result = 31 * result + Integer.hashCode(flags);
			result = 31 * result + Long.hashCode(visibilityData);
			result = 31 * result + Integer.hashCode(lastUploadFrame);
			result = 31 * result + Integer.hashCode(pendingUpdateType);
			result = 31 * result + Long.hashCode(pendingUpdateSince);
			return 31 * result + System.identityHashCode(identity);
		}
	}

	record GlobalEntitySectionState(
		SectionState section,
		List<GlobalBlockEntityState> blockEntities
	) {
		public GlobalEntitySectionState {
			Objects.requireNonNull(section, "section");
			blockEntities = List.copyOf(blockEntities);
		}
	}

	/**
	 * Keeps exact object identity so replacing an entity at the same position invalidates scene stability.
	 */
	record GlobalBlockEntityState(long position, BlockEntity identity) {
		public GlobalBlockEntityState {
			Objects.requireNonNull(identity, "identity");
		}

		@Override
		public boolean equals(Object other) {
			return this == other || other instanceof GlobalBlockEntityState state &&
				position == state.position && identity == state.identity;
		}

		@Override
		public int hashCode() {
			return 31 * Long.hashCode(position) + System.identityHashCode(identity);
		}
	}

	record ShadowTaskState(
		TaskQueueType queueType,
		long sectionPosition,
		int pendingUpdateType,
		long pendingUpdateSince,
		Object identity
	) {
		public ShadowTaskState {
			Objects.requireNonNull(queueType, "queueType");
			Objects.requireNonNull(identity, "identity");
		}

		@Override
		public boolean equals(Object other) {
			return this == other || other instanceof ShadowTaskState state && queueType == state.queueType &&
				sectionPosition == state.sectionPosition && pendingUpdateType == state.pendingUpdateType &&
				pendingUpdateSince == state.pendingUpdateSince && identity == state.identity;
		}

		@Override
		public int hashCode() {
			int result = queueType.hashCode();
			result = 31 * result + Long.hashCode(sectionPosition);
			result = 31 * result + Integer.hashCode(pendingUpdateType);
			result = 31 * result + Long.hashCode(pendingUpdateSince);
			return 31 * result + System.identityHashCode(identity);
		}
	}
}
