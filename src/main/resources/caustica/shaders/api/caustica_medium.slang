module caustica_medium;

import caustica_types;

public interface IMediumModel {
    public MediumProperties createMedium(MediumInput input);
    public PhaseEvaluation evaluatePhase(PhaseQuery query);
    public PhaseSample samplePhase(PhaseSampleQuery query);
};
