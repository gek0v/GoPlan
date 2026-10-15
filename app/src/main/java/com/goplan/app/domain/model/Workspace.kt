package com.goplan.app.domain.model

import java.io.File

data class Workspace(
    val id: String,
    val name: String,
    val rootDir: File
)
