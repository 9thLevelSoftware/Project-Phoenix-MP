package com.devil.phoenixproject.data.context

data class VendorContext(
    val vendorId: String,
    val protocolVersion: String
)

class VendorContextProvider(
    initialContext: VendorContext = DEFAULT_CONTEXT
) {
    private var context: VendorContext = initialContext

    fun current(): VendorContext = context

    fun setActiveContext(newContext: VendorContext) {
        context = newContext
    }

    companion object {
        val DEFAULT_CONTEXT = VendorContext(
            vendorId = "phoenix",
            protocolVersion = "v1"
        )
    }
}
