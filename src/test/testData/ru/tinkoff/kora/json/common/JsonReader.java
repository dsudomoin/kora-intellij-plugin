package ru.tinkoff.kora.json.common;

public interface JsonReader<T> {
    T read(Object parser) throws Exception;
}
