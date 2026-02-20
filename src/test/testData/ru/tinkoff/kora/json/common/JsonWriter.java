package ru.tinkoff.kora.json.common;

public interface JsonWriter<T> {
    void write(Object generator, T value) throws Exception;
}
