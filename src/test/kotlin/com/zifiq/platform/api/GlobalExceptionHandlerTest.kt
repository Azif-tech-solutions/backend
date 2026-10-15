package com.zifiq.platform.api

import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * A request the framework rejects before any controller code runs is the client's mistake, and the
 * response says which one (415, 405, 400) instead of a bare 500 — e.g. an upload sent without its
 * file, which some clients send with no multipart body at all.
 */
class GlobalExceptionHandlerTest {
    @RestController
    class UploadProbe {
        @PutMapping("/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
        fun upload(@RequestPart("file") file: MultipartFile) = ApiEnvelope(file.size)
    }

    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(UploadProbe()).setControllerAdvice(GlobalExceptionHandler()).build()

    @Test
    fun `an upload sent without a multipart body is 415 naming the expected type`() {
        mvc.perform(put("/upload").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"))
            .andExpect(jsonPath("$.error.details.supported").value(MediaType.MULTIPART_FORM_DATA_VALUE))
    }

    @Test
    fun `a multipart upload without the file part is 400 naming the part`() {
        mvc.perform(multipart(HttpMethod.PUT, "/upload").file(MockMultipartFile("other", "x.png", "image/png", byteArrayOf(1))))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.error.details.file").exists())
    }

    @Test
    fun `a method the route doesn't take is 405 listing the ones it does`() {
        mvc.perform(get("/upload"))
            .andExpect(status().isMethodNotAllowed)
            .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"))
            .andExpect(jsonPath("$.error.details.supported").value("PUT"))
    }
}
