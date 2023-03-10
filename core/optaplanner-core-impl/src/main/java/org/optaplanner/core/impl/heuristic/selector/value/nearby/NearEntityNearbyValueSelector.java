package org.optaplanner.core.impl.heuristic.selector.value.nearby;

import java.util.Iterator;
import java.util.Objects;

import org.optaplanner.core.impl.domain.variable.descriptor.GenuineVariableDescriptor;
import org.optaplanner.core.impl.heuristic.selector.common.iterator.SelectionIterator;
import org.optaplanner.core.impl.heuristic.selector.common.nearby.NearbyDistanceMatrix;
import org.optaplanner.core.impl.heuristic.selector.common.nearby.NearbyDistanceMatrixDemand;
import org.optaplanner.core.impl.heuristic.selector.common.nearby.NearbyDistanceMeter;
import org.optaplanner.core.impl.heuristic.selector.common.nearby.NearbyRandom;
import org.optaplanner.core.impl.heuristic.selector.entity.EntitySelector;
import org.optaplanner.core.impl.heuristic.selector.entity.mimic.MimicReplayingEntitySelector;
import org.optaplanner.core.impl.heuristic.selector.value.AbstractValueSelector;
import org.optaplanner.core.impl.heuristic.selector.value.ValueSelector;
import org.optaplanner.core.impl.phase.scope.AbstractPhaseScope;
import org.optaplanner.core.impl.solver.scope.SolverScope;
import org.optaplanner.core.impl.util.MemoizingSupply;

public final class NearEntityNearbyValueSelector<Solution_> extends AbstractValueSelector<Solution_> {

    private final ValueSelector<Solution_> childValueSelector;
    private final EntitySelector<Solution_> replayingOriginEntitySelector;
    private final NearbyDistanceMeter<?, ?> nearbyDistanceMeter;
    private final NearbyRandom nearbyRandom;
    private final boolean randomSelection;
    private final boolean discardNearbyIndexZero;
    private final NearbyDistanceMatrixDemand<Solution_, ?, ?> nearbyDistanceMatrixDemand;

    private MemoizingSupply<NearbyDistanceMatrix> nearbyDistanceMatrixSupply = null;

    public NearEntityNearbyValueSelector(ValueSelector<Solution_> childValueSelector,
            EntitySelector<Solution_> originEntitySelector, NearbyDistanceMeter<?, ?> nearbyDistanceMeter,
            NearbyRandom nearbyRandom, boolean randomSelection) {
        this.childValueSelector = childValueSelector;
        if (!(originEntitySelector instanceof MimicReplayingEntitySelector)) {
            // In order to select a nearby value, we must first have something to be near by.
            throw new IllegalStateException("Impossible state: Nearby value selector (" + this +
                    ") did not receive a replaying entity selector (" + originEntitySelector + ").");
        }
        this.replayingOriginEntitySelector = originEntitySelector;
        this.nearbyDistanceMeter = nearbyDistanceMeter;
        this.nearbyRandom = nearbyRandom;
        this.randomSelection = randomSelection;
        if (randomSelection && nearbyRandom == null) {
            throw new IllegalArgumentException("The valueSelector (" + this
                    + ") with randomSelection (" + randomSelection + ") has no nearbyRandom (" + nearbyRandom + ").");
        }
        this.discardNearbyIndexZero = childValueSelector.getVariableDescriptor().getVariablePropertyType().isAssignableFrom(
                originEntitySelector.getEntityDescriptor().getEntityClass());
        this.nearbyDistanceMatrixDemand =
                new NearbyDistanceMatrixDemand<>(nearbyDistanceMeter, childValueSelector, replayingOriginEntitySelector,
                        this::computeDestinationSize);
        phaseLifecycleSupport.addEventListener(childValueSelector);
        phaseLifecycleSupport.addEventListener(originEntitySelector);
    }

    @Override
    public GenuineVariableDescriptor<Solution_> getVariableDescriptor() {
        return childValueSelector.getVariableDescriptor();
    }

    @Override
    public void solvingStarted(SolverScope<Solution_> solverScope) {
        super.solvingStarted(solverScope);
        /*
         * Supply will ask questions of the child selector.
         * However, child selector will only be initialized during phase start.
         * Yet we still want the very expensive nearby distance matrix to be reused across phases.
         * Therefore we request the supply here, but actually lazily initialize it during phase start.
         */
        nearbyDistanceMatrixSupply = (MemoizingSupply) solverScope.getScoreDirector().getSupplyManager()
                .demand(nearbyDistanceMatrixDemand);
    }

    @Override
    public void phaseStarted(AbstractPhaseScope<Solution_> phaseScope) {
        super.phaseStarted(phaseScope);
        // Lazily initialize the supply, so that steps can then have uniform performance.
        nearbyDistanceMatrixSupply.read();
    }

    private int computeDestinationSize(Object origin) {
        long childSize = childValueSelector.getSize(origin);
        if (childSize > Integer.MAX_VALUE) {
            throw new IllegalStateException("The childEntitySelector (" + childValueSelector
                    + ") has an entitySize (" + childSize
                    + ") which is higher than Integer.MAX_VALUE.");
        }
        int destinationSize = (int) childSize;
        if (randomSelection) {
            // Reduce RAM memory usage by reducing destinationSize if nearbyRandom will never select a higher value
            int overallSizeMaximum = nearbyRandom.getOverallSizeMaximum();
            if (discardNearbyIndexZero && overallSizeMaximum < Integer.MAX_VALUE) {
                overallSizeMaximum++;
            }
            if (destinationSize > overallSizeMaximum) {
                destinationSize = overallSizeMaximum;
            }
        }
        return destinationSize;
    }

    @Override
    public void solvingEnded(SolverScope<Solution_> solverScope) {
        super.solvingEnded(solverScope);
        solverScope.getScoreDirector().getSupplyManager().cancel(nearbyDistanceMatrixDemand);
        nearbyDistanceMatrixSupply = null;
    }

    // ************************************************************************
    // Worker methods
    // ************************************************************************

    @Override
    public boolean isCountable() {
        return childValueSelector.isCountable();
    }

    @Override
    public boolean isNeverEnding() {
        return randomSelection || !isCountable();
    }

    @Override
    public long getSize(Object entity) {
        return childValueSelector.getSize(entity) - (discardNearbyIndexZero ? 1 : 0);
    }

    @Override
    public Iterator<Object> iterator(Object entity) {
        Iterator<Object> replayingOriginEntityIterator = replayingOriginEntitySelector.iterator();
        if (!randomSelection) {
            return new OriginalEntityNearbyValueIterator(replayingOriginEntityIterator, childValueSelector.getSize(entity));
        } else {
            return new RandomEntityNearbyValueIterator(replayingOriginEntityIterator, childValueSelector.getSize(entity));
        }
    }

    @Override
    public Iterator<Object> endingIterator(Object entity) {
        // TODO It should probably use nearby order
        // It must include the origin entity too
        return childValueSelector.endingIterator(entity);
    }

    private final class OriginalEntityNearbyValueIterator extends SelectionIterator<Object> {

        private final Iterator<Object> replayingOriginEntityIterator;
        private final long childSize;

        private boolean originSelected = false;
        private boolean originIsNotEmpty;
        private Object origin;

        private int nextNearbyIndex;

        public OriginalEntityNearbyValueIterator(Iterator<Object> replayingOriginEntityIterator, long childSize) {
            this.replayingOriginEntityIterator = replayingOriginEntityIterator;
            this.childSize = childSize;
            nextNearbyIndex = discardNearbyIndexZero ? 1 : 0;
        }

        private void selectOrigin() {
            if (originSelected) {
                return;
            }
            /*
             * The origin iterator is guaranteed to be a replaying iterator.
             * Therefore next() will point to whatever that the related recording iterator was pointing to at the time
             * when its next() was called.
             * As a result, origin here will be constant unless next() on the original recording iterator is called
             * first.
             */
            originIsNotEmpty = replayingOriginEntityIterator.hasNext();
            origin = replayingOriginEntityIterator.next();
            originSelected = true;
        }

        @Override
        public boolean hasNext() {
            selectOrigin();
            return originIsNotEmpty && nextNearbyIndex < childSize;
        }

        @Override
        public Object next() {
            selectOrigin();
            Object next = nearbyDistanceMatrixSupply.read().getDestination(origin, nextNearbyIndex);
            nextNearbyIndex++;
            return next;
        }

    }

    private final class RandomEntityNearbyValueIterator extends SelectionIterator<Object> {

        private final Iterator<Object> replayingOriginEntityIterator;
        private final int nearbySize;

        public RandomEntityNearbyValueIterator(Iterator<Object> replayingOriginEntityIterator, long childSize) {
            this.replayingOriginEntityIterator = replayingOriginEntityIterator;
            if (childSize > Integer.MAX_VALUE) {
                throw new IllegalStateException("The valueSelector (" + this
                        + ") has an entitySize (" + childSize
                        + ") which is higher than Integer.MAX_VALUE.");
            }
            nearbySize = (int) childSize - (discardNearbyIndexZero ? 1 : 0);
        }

        @Override
        public boolean hasNext() {
            return replayingOriginEntityIterator.hasNext() && nearbySize > 0;
        }

        @Override
        public Object next() {
            /*
             * The origin iterator is guaranteed to be a replaying iterator.
             * Therefore next() will point to whatever that the related recording iterator was pointing to at the time
             * when its next() was called.
             * As a result, origin here will be constant unless next() on the original recording iterator is called
             * first.
             */
            Object origin = replayingOriginEntityIterator.next();
            int nearbyIndex = nearbyRandom.nextInt(workingRandom, nearbySize);
            if (discardNearbyIndexZero) {
                nearbyIndex++;
            }
            return nearbyDistanceMatrixSupply.read().getDestination(origin, nearbyIndex);
        }

    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (other == null || getClass() != other.getClass())
            return false;
        NearEntityNearbyValueSelector<?> that = (NearEntityNearbyValueSelector<?>) other;
        return Objects.equals(childValueSelector, that.childValueSelector)
                && Objects.equals(replayingOriginEntitySelector, that.replayingOriginEntitySelector)
                && Objects.equals(nearbyDistanceMeter, that.nearbyDistanceMeter)
                && Objects.equals(nearbyRandom, that.nearbyRandom);
    }

    @Override
    public int hashCode() {
        return Objects.hash(childValueSelector, replayingOriginEntitySelector, nearbyDistanceMeter, nearbyRandom);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + replayingOriginEntitySelector + ", " + childValueSelector + ")";
    }

}
