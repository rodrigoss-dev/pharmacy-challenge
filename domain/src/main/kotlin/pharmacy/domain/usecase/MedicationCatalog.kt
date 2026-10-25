package pharmacy.domain.usecase

import pharmacy.domain.DomainLog
import pharmacy.domain.Medication
import pharmacy.domain.MedicationId
import pharmacy.domain.NotFoundException
import pharmacy.domain.ValidationException
import pharmacy.domain.port.InventoryRepository

class MedicationCatalog(private val inventory: InventoryRepository) {
    private val log = DomainLog.of<MedicationCatalog>()

    fun list(): List<Medication> = inventory.findAll()

    fun restock(id: MedicationId, quantity: Int): Medication {
        if (quantity !in 1..1000) {
            log.info { "Restock of $id refused: $quantity packs is outside 1..1000" }
            throw ValidationException("Restock between 1 and 1000 packs")
        }
        inventory.findByIds(listOf(id)).singleOrNull()
            ?: throw NotFoundException("Medication $id not found").also { log.info { it.message!! } }
        inventory.addStock(id, quantity)
        return inventory.findByIds(listOf(id)).single().also {
            log.info { "Restocked ${it.name}: +$quantity packs, ${it.stock} now available" }
        }
    }
}
