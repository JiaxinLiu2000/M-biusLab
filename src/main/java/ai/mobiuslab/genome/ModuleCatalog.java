package ai.mobiuslab.genome;

import ai.mobiuslab.common.Actor;

import java.util.List;

/** Catalog of reusable node modules discovered by evolution. */
public interface ModuleCatalog {

    /** Registers a module version (next version when version &lt;= 0); idempotent on (moduleId, version). */
    ModuleDef register(ModuleDef def, Actor actor);

    /** Module by id and version; throws NOT_FOUND. */
    ModuleDef get(String moduleId, int version);

    /** Searches by optional kind and optional text (name/description), most reused first. */
    List<ModuleDef> search(NodeKind kind, String text, int limit);

    /** Increments the reuse counter of a module version. */
    void markReused(String moduleId, int version);
}
