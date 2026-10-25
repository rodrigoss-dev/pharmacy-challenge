package pharmacy.infra

import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import pharmacy.domain.port.EventOutbox
import pharmacy.domain.port.GovernmentCommitOutbox
import pharmacy.domain.port.GovernmentPrescriptions
import pharmacy.domain.port.InventoryRepository
import pharmacy.domain.port.PrescriptionRepository
import pharmacy.domain.usecase.ListWorkQueues
import pharmacy.domain.usecase.LookUpGovernmentPrescription
import pharmacy.domain.usecase.MedicationCatalog
import pharmacy.domain.usecase.PrescriptionUpdater
import pharmacy.domain.usecase.ReserveStock
import pharmacy.domain.usecase.StaffActions
import pharmacy.domain.usecase.StalledStockChecks
import pharmacy.domain.usecase.SubmitPrescription
import pharmacy.domain.usecase.TrackPrescription
import pharmacy.domain.usecase.WaitingRoomBoard
import java.time.Clock

/**
 * Makes the domain's use cases injectable, wired to the adapters that implement its ports. The domain itself knows
 * nothing about CDI. REST resources and RabbitMQ consumers inject these; nothing else calls into the domain.
 *
 * Nor does the domain know about transactions: whoever calls a use case runs it in one, through
 * `persistence/Transactions` (REST endpoints that change something, the stock check consumer, the sweeper). Everything
 * the use case writes, its outbox event included, then commits together or not at all.
 */
@Singleton
class UseCases {
    @Produces
    @Singleton
    fun clock(): Clock = Clock.systemUTC()

    @Produces
    @Singleton
    fun submitPrescription(
        prescriptions: PrescriptionRepository,
        inventory: InventoryRepository,
        outbox: EventOutbox,
        clock: Clock,
    ) = SubmitPrescription(prescriptions, inventory, outbox, clock)

    @Produces
    @Singleton
    fun prescriptionUpdater(prescriptions: PrescriptionRepository, outbox: EventOutbox) =
        PrescriptionUpdater(prescriptions, outbox)

    @Produces
    @Singleton
    fun reserveStock(
        updater: PrescriptionUpdater,
        prescriptions: PrescriptionRepository,
        inventory: InventoryRepository,
        clock: Clock,
    ) = ReserveStock(updater, prescriptions, inventory, clock)

    @Produces
    @Singleton
    fun staffActions(
        updater: PrescriptionUpdater,
        inventory: InventoryRepository,
        clock: Clock,
        reserveStock: ReserveStock,
        governmentCommits: GovernmentCommitOutbox,
    ) = StaffActions(updater, inventory, clock, reserveStock, governmentCommits)

    @Produces
    @Singleton
    fun lookUpGovernmentPrescription(government: GovernmentPrescriptions) = LookUpGovernmentPrescription(government)

    @Produces
    @Singleton
    fun stalledStockChecks(prescriptions: PrescriptionRepository, clock: Clock) = StalledStockChecks(prescriptions, clock)

    @Produces
    @Singleton
    fun trackPrescription(prescriptions: PrescriptionRepository, clock: Clock) = TrackPrescription(prescriptions, clock)

    @Produces
    @Singleton
    fun waitingRoomBoard(prescriptions: PrescriptionRepository) = WaitingRoomBoard(prescriptions)

    @Produces
    @Singleton
    fun listWorkQueues(prescriptions: PrescriptionRepository) = ListWorkQueues(prescriptions)

    @Produces
    @Singleton
    fun medicationCatalog(inventory: InventoryRepository) = MedicationCatalog(inventory)
}
