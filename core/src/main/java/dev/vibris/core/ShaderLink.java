package dev.vibris.core;

interface ShaderLink {
    void switchTo(SourceRegistry.Lease source, OwnershipCheck ownership) throws Failure;

    void detach() throws Failure;

    boolean retainsActiveSource();

    static ShaderLink transientLink() {
        return Transient.INSTANCE;
    }

    @FunctionalInterface
    interface OwnershipCheck {
        void verify() throws Failure;
    }

    final class Failure extends Exception {
        private final boolean stable;

        Failure(String message, boolean stable) {
            super(message);
            this.stable = stable;
        }

        Failure(String message, boolean stable, Throwable cause) {
            super(message, cause);
            this.stable = stable;
        }

        boolean stable() {
            return stable;
        }
    }

    enum Transient implements ShaderLink {
        INSTANCE;

        @Override
        public void switchTo(SourceRegistry.Lease source, OwnershipCheck ownership) throws Failure {
            ownership.verify();
        }

        @Override
        public void detach() {
        }

        @Override
        public boolean retainsActiveSource() {
            return false;
        }
    }
}