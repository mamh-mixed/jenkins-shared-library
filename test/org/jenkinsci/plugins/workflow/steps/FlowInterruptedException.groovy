package org.jenkinsci.plugins.workflow.steps

class FlowInterruptedException extends Exception {
    private final List<Object> interruptionCauses

    FlowInterruptedException(List<Object> interruptionCauses) {
        this.interruptionCauses = interruptionCauses
    }

    List<Object> getCauses() {
        return interruptionCauses
    }
}

class ExceededTimeout {
}

class UserInterruption {
}
