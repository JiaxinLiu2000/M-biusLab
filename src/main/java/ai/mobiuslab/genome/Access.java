package ai.mobiuslab.genome;

/** Access level granted by the master agent. */
public enum Access {
    NONE,
    READ,
    READ_WRITE;

    /** True when reading is allowed. */
    public boolean canRead() {
        return this != NONE;
    }

    /** True when writing is allowed. */
    public boolean canWrite() {
        return this == READ_WRITE;
    }
}
