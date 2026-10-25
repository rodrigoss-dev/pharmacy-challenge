package pharmacy.infra.api

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.jackson.ObjectMapperCustomizer
import jakarta.inject.Singleton

/** How the API reads JSON: stricter than Jackson's defaults where a lenient reading would change what was asked for. */
@Singleton
class JsonConfiguration : ObjectMapperCustomizer {
    override fun customize(mapper: ObjectMapper) {
        // 1.5 packs would be cut to 1 without a word. A whole number written as 2.0 is refused as well: nobody sends it.
        mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
    }
}
