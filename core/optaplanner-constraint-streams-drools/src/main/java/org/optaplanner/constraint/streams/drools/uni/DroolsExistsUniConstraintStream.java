package org.optaplanner.constraint.streams.drools.uni;

import java.util.function.Predicate;
import java.util.function.Supplier;

import org.optaplanner.constraint.streams.drools.DroolsConstraintFactory;
import org.optaplanner.constraint.streams.drools.common.UniLeftHandSide;
import org.optaplanner.core.api.score.stream.bi.BiJoiner;

public final class DroolsExistsUniConstraintStream<Solution_, A> extends DroolsAbstractUniConstraintStream<Solution_, A> {

    private final DroolsAbstractUniConstraintStream<Solution_, A> parent;
    private final Supplier<UniLeftHandSide<A>> leftHandSide;
    private final String streamName;

    public <B> DroolsExistsUniConstraintStream(DroolsConstraintFactory<Solution_> constraintFactory,
            DroolsAbstractUniConstraintStream<Solution_, A> parent, boolean shouldExist, boolean shouldIncludeNullVars,
            Class<B> otherClass, BiJoiner<A, B>... joiners) {
        super(constraintFactory, parent.getRetrievalSemantics());
        this.parent = parent;
        Predicate<B> nullityFilter = shouldIncludeNullVars ? null : constraintFactory.getNullityFilter(otherClass);
        this.leftHandSide = () -> shouldExist
                ? parent.createLeftHandSide().andExists(otherClass, joiners, nullityFilter)
                : parent.createLeftHandSide().andNotExists(otherClass, joiners, nullityFilter);
        this.streamName = shouldExist ? "IfExists()" : "IfNotExists()";
    }

    @Override
    public boolean guaranteesDistinct() {
        return parent.guaranteesDistinct();
    }

    // ************************************************************************
    // Pattern creation
    // ************************************************************************

    @Override
    public UniLeftHandSide<A> createLeftHandSide() {
        return leftHandSide.get();
    }

    @Override
    public String toString() {
        return streamName + " with " + getChildStreams().size() + " children";
    }
}
