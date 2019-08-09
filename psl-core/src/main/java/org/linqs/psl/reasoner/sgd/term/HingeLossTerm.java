/*
 * This file is part of the PSL software.
 * Copyright 2011-2015 University of Maryland
 * Copyright 2013-2019 The Regents of the University of California
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.linqs.psl.reasoner.sgd.term;

import org.linqs.psl.model.atom.RandomVariableAtom;
import org.linqs.psl.reasoner.term.Hyperplane;

/**
 * Objective term of the form: weight * max(0.0, coeffs^T * x - constant)
 * All coeffs must be non-zero.
 */
public class HingeLossTerm extends SGDObjectiveTerm {
    public HingeLossTerm(Hyperplane<RandomVariableAtom> hyperplane, float weight, float learningRate) {
        super(hyperplane, weight, learningRate);
    }

    @Override
    protected float computeGradient(int iteration, int varId, float dot) {
        return weight * (learningRate / iteration) * coefficients[varId];
    }

    /**
     * weight * max(0.0, coeffs^T * x - constant)
     */
    @Override
    public float evaluate() {
        return weight * Math.max(0.0f, dot());
    }
}