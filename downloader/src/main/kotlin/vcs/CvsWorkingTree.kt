/*
 * Copyright (C) 2022 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.downloader.vcs

import java.io.File
import java.security.MessageDigest

import org.ossreviewtoolkit.downloader.WorkingTree
import org.ossreviewtoolkit.model.VcsType
import org.ossreviewtoolkit.utils.common.ProcessCapture
import org.ossreviewtoolkit.utils.common.encodeHex
import org.ossreviewtoolkit.utils.common.safeDeleteRecursively
import org.ossreviewtoolkit.utils.common.searchUpwardsForSubdirectory

class CvsWorkingTree(workingDir: File, vcsType: VcsType) : WorkingTree(workingDir, vcsType) {
    private val cvsDirectory = workingDir.resolve("CVS")

    override fun isValid(): Boolean {
        if (!workingDir.isDirectory) {
            return false
        }

        return ProcessCapture(workingDir, "cvs", "status", "-l").isSuccess
    }

    override fun isShallow() = false

    override fun getRemoteUrl() = cvsDirectory.resolve("Root").useLines { it.first() }

    override fun getRevision() =
        // CVS does not have the concept of a global revision, but each file has its own revision. As
        // we just use the revision to uniquely identify the state of a working tree, simply create an
        // artificial single revision based on the revisions of all files.
        getFileRevisionsHash(getFileRevisions())

    override fun getRootPath(): File {
        val rootDir = workingDir.searchUpwardsForSubdirectory("CVS")
        return rootDir ?: workingDir
    }

    override fun listRemoteBranches() =
        listSymbolicNames().mapNotNull { (name, version) ->
            name.takeIf { isBranchVersion(version) }
        }

    override fun listRemoteTags() =
        listSymbolicNames().mapNotNull { (name, version) ->
            name.takeUnless { isBranchVersion(version) }
        }

    private fun getFileRevisions(): CvsFileRevisions {
        val cvsLog = CvsCommand.run(workingDir, "-n", "log", "-h")

        var currentWorkingFile = ""
        return cvsLog.stdout.lines().mapNotNull { line ->
            var value = line.removePrefix("Working file: ")
            if (value.length < line.length) {
                currentWorkingFile = value
            } else {
                value = line.removePrefix("head: ")
                if (value.length < line.length) {
                    if (currentWorkingFile.isNotBlank() && value.isNotBlank()) {
                        return@mapNotNull Pair(currentWorkingFile, value)
                    }
                }
            }

            null
        }.sortedBy { it.first }
    }

    private fun getFileRevisionsHash(fileRevisions: CvsFileRevisions) =
        fileRevisions.fold(MessageDigest.getInstance("SHA-1")) { digest, (file, revision) ->
            digest.apply {
                update(file.toByteArray())
                update(revision.toByteArray())
            }
        }.digest().encodeHex()

    private fun listSymbolicNames(): Map<String, String> =
        try {
            // Create all working tree directories in order to be able to query the log.
            CvsCommand.run(workingDir, "update", "-d")

            val cvsLog = CvsCommand.run(workingDir, "-n", "log", "-h")
            var tagsSectionStarted = false

            cvsLog.stdout.lines().mapNotNull { line ->
                if (tagsSectionStarted) {
                    if (line.startsWith('\t')) {
                        line.split(':', limit = 2).let {
                            Pair(it.first().trim(), it.last().trim())
                        }
                    } else {
                        tagsSectionStarted = false
                        null
                    }
                } else {
                    if (line == "symbolic names:") {
                        tagsSectionStarted = true
                    }
                    null
                }
            }.toMap().toSortedMap()
        } finally {
            // Clean the temporarily updated working tree again.
            workingDir.walk().maxDepth(1).forEach {
                if (it.isDirectory) {
                    if (it != workingDir && it.name != "CVS") it.safeDeleteRecursively()
                } else {
                    it.delete()
                }
            }
        }

    private fun isBranchVersion(version: String): Boolean {
        // See http://cvsgui.sourceforge.net/howto/cvsdoc/cvs_5.html#SEC59.
        val decimals = version.split('.')

        // "Externally, branch numbers consist of an odd number of dot-separated decimal
        // integers."
        return decimals.size % 2 != 0 ||
                // "That is not the whole truth, however. For efficiency reasons CVS sometimes inserts
                // an extra 0 in the second rightmost position."
                decimals.dropLast(1).last() == "0"
    }
}
