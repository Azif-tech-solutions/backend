package com.zifiq

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.modulith.Modulithic
import org.springframework.scheduling.annotation.EnableScheduling

@Modulithic
@SpringBootApplication
@EnableScheduling
class ZifiqApplication

fun main(args: Array<String>) {
    runApplication<ZifiqApplication>(*args)
}
