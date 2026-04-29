package ai.datris.config;

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Method/class-level annotation: only allow users whose role is in {@code value()} to call this endpoint.
 *
 * <p>Why this is Java and not Scala: Scala 2.12 cannot emit a runtime-visible Java annotation.
 * The Scala compiler itself prints "subclassing ClassfileAnnotation does not make your annotation
 * visible at runtime — you must write the annotation class in Java." Spring's reflection-based
 * scanning needs a real Java annotation, so this stays as a .java file.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresRole {
    String[] value();
}
