package pharmacy.infra.api

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import org.jboss.resteasy.reactive.RestPath
import pharmacy.domain.MedicationId
import pharmacy.domain.Prescription
import pharmacy.domain.PrescriptionId
import pharmacy.domain.usecase.ListWorkQueues
import pharmacy.domain.usecase.MedicationCatalog
import pharmacy.domain.usecase.StaffActions
import pharmacy.infra.persistence.Transactions

// No authentication: out of scope for the challenge. In production these would sit behind staff login.
@Path("/api/staff")
class StaffResource(
    private val listWorkQueues: ListWorkQueues,
    private val staffActions: StaffActions,
    private val medicationCatalog: MedicationCatalog,
    private val transactions: Transactions,
) {
    @GET
    @Path("/queues")
    fun queues(): WorkQueuesResponse = transactions.read("listing work queues") { listWorkQueues() }.toResponse()

    @POST
    @Path("/prescriptions/{id}/approve")
    fun approve(@RestPath id: String) = act("approve", id) { approve(it) }

    @POST
    @Path("/prescriptions/{id}/reject")
    fun reject(@RestPath id: String, request: RejectRequest?) = act("reject", id) { reject(it, request?.reason.orEmpty()) }

    @POST
    @Path("/prescriptions/{id}/packed")
    fun packed(@RestPath id: String) = act("mark packed", id) { markPacked(it) }

    @POST
    @Path("/prescriptions/{id}/call")
    fun call(@RestPath id: String) = act("call", id) { callPatient(it) }

    @POST
    @Path("/prescriptions/{id}/hand-over")
    fun handOver(@RestPath id: String) = act("hand over", id) { handOver(it) }

    @POST
    @Path("/prescriptions/{id}/no-show")
    fun noShow(@RestPath id: String) = act("no show", id) { noShow(it) }

    @POST
    @Path("/prescriptions/{id}/retry-stock-check")
    fun retryStockCheck(@RestPath id: String) = act("retry stock check", id) { retryStockCheck(it) }

    @POST
    @Path("/medications/{id}/restock")
    fun restock(@RestPath id: String, request: RestockRequest?): MedicationResponse {
        val quantity = request?.quantity ?: throw BadRequestException("Say how many packs to add")
        return transactions.transaction("restock $id") { medicationCatalog.restock(MedicationId(id), quantity) }.toResponse()
    }

    private fun act(action: String, id: String, step: StaffActions.(PrescriptionId) -> Prescription): StaffPrescriptionResponse {
        val prescriptionId = id.toPrescriptionId()
        return transactions.transaction("$action $prescriptionId") { staffActions.step(prescriptionId) }.toStaffResponse()
    }
}
