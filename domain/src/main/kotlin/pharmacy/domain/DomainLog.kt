package pharmacy.domain

import java.lang.System.Logger.Level

/**
 * The domain's logger: the JDK's own `System.Logger`, so the domain still depends on nothing. Whatever runs the domain
 * decides where the lines go; Quarkus writes them to its log like any other category (`pharmacy.domain...`), with the
 * same levels and configuration.
 *
 * Messages are built only when their level is enabled. Prescriptions are named by ticket number and id, never by the
 * patient's name.
 */
internal class DomainLog(name: String) {
    private val logger = System.getLogger(name)

    fun debug(message: () -> String) = log(Level.DEBUG, message)

    fun info(message: () -> String) = log(Level.INFO, message)

    fun warn(message: () -> String) = log(Level.WARNING, message)

    private fun log(level: Level, message: () -> String) {
        if (logger.isLoggable(level)) logger.log(level, message())
    }

    companion object {
        inline fun <reified T : Any> of() = DomainLog(T::class.java.name)
    }
}

/** e.g. `2x Amoxicillin 500 mg, 1x Ibuprofen 400 mg`, or `nothing`. */
internal fun List<PrescriptionItem>.summary(): String =
    if (isEmpty()) "nothing" else joinToString { "${it.quantity}x ${it.medicationName}" }
