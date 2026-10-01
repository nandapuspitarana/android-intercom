package com.intercom.video.twoway.functional

/**
 * Marks a functional test: drives the real UI and service end to end against an in-process
 * [FakePeerDevice]. `./gradlew functionalTest` runs only classes carrying this annotation.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class FunctionalTest
