package io.github.vipcxj.asyncjava;

public class ReturnException extends RuntimeException {

    private final Object value;

    public ReturnException(Object value) {
        super("", null, false, false);
        this.value = value;
    }

    public Object getValue() {
        return value;
    }
}
