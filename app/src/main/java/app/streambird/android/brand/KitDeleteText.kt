package app.streambird.android.brand

/** Texts of the "delete brand kit" confirmation and result, shared by the kit cards and the kit editor. */
object KitDeleteText {
    fun title(name: String) = "Delete \"$name\"?"
    const val BODY = "Channels that use this kit go back to the account default. Your uploaded files stay in storage."
    const val CONFIRM = "Delete kit"
    const val CANCEL = "Cancel"
    fun done(name: String) = "Deleted \"$name\"."
    fun iconDescription(name: String) = "Delete $name"
}
