package dev.comfyfluffy.caustica.api.provider;

public interface MaterialSource extends ProviderLifecycle {
    /** Submit this resource epoch's definitions and ordered rules. */
    void submitMaterials(MaterialSink sink);
}
