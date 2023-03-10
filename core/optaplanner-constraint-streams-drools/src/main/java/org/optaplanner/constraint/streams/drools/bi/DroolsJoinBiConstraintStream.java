package org.optaplanner.constraint.streams.drools.bi;

import java.util.function.Supplier;

import org.optaplanner.constraint.streams.drools.DroolsConstraintFactory;
import org.optaplanner.constraint.streams.drools.common.BiLeftHandSide;
import org.optaplanner.constraint.streams.drools.uni.DroolsAbstractUniConstraintStream;
import org.optaplanner.core.api.score.stream.bi.BiJoiner;

public final class DroolsJoinBiConstraintStream<Solution_, A, B>
        extends DroolsAbstractBiConstraintStream<Solution_, A, B> {

    private final Supplier<BiLeftHandSide<A, B>> leftHandSide;
    private final boolean guaranteesDistinct;

    public DroolsJoinBiConstraintStream(DroolsConstraintFactory<Solution_> constraintFactory,
            DroolsAbstractUniConstraintStream<Solution_, A> parent,
            DroolsAbstractUniConstraintStream<Solution_, B> otherStream, BiJoiner<A, B> biJoiner) {
        super(constraintFactory, parent.getRetrievalSemantics());
        this.leftHandSide = () -> parent.createLeftHandSide().andJoin(otherStream.createLeftHandSide(), biJoiner);
        this.guaranteesDistinct = parent.guaranteesDistinct() && otherStream.guaranteesDistinct();
    }

    @Override
    public boolean guaranteesDistinct() {
        return guaranteesDistinct;
    }

    @Override
    public BiLeftHandSide<A, B> createLeftHandSide() {
        return leftHandSide.get();
    }

    @Override
    public String toString() {
        return "BiJoin() with " + getChildStreams().size() + " children";
    }

}
