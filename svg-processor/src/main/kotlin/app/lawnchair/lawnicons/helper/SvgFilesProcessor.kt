/*
 * Copyright 2024 Lawnchair Launcher
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair.lawnicons.helper

import com.android.ide.common.vectordrawable.Svg2Vector
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet
import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.dom4j.Document
import org.dom4j.DocumentException
import org.dom4j.DocumentHelper

object SvgFilesProcessor {
    private const val PLATE_NAME = "lawnicons_plate"

    // Outer ring thickness on a 192-unit icon (8 is about 4% of the icon, 12 is about 6%).
    private const val RING_WIDTH = 8
    private lateinit var sourceSvgPath: Path
    private lateinit var destinationVectorPath: Path
    fun process(sourceDirectory: String, destDirectory: String) {
        this.sourceSvgPath = Paths.get(sourceDirectory)
        this.destinationVectorPath = Paths.get(destDirectory)
        try {
            val options = EnumSet.of(FileVisitOption.FOLLOW_LINKS)
            // check first if source is a directory
            if (Files.isDirectory(sourceSvgPath)) {
                Files.walkFileTree(sourceSvgPath, options, Int.MAX_VALUE, fileVisitor)
            } else {
                println("source not a directory")
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private val fileVisitor = object : FileVisitor<Path> {
        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult = FileVisitResult.CONTINUE

        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes?): FileVisitResult {
            // Skip folder which is processing svgs to xml
            if (dir == destinationVectorPath) {
                return FileVisitResult.SKIP_SUBTREE
            }
            val newDirectory = destinationVectorPath.resolve(sourceSvgPath.relativize(dir))
            try {
                Files.createDirectories(newDirectory)
            } catch (e: FileAlreadyExistsException) {
                e.printStackTrace()
            } catch (e: IOException) {
                e.printStackTrace()
                return FileVisitResult.SKIP_SUBTREE
            }
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes?): FileVisitResult {
            convertToVector(file, destinationVectorPath.resolve(sourceSvgPath.relativize(file)))
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult = FileVisitResult.CONTINUE
    }

    private fun convertToVector(svgSource: Path, vectorTargetPath: Path) {
        // convert only if it is .svg
        if (svgSource.fileName.toString().endsWith(".svg")) {
            val targetFile = XmlUtil.getFileWithExtension(vectorTargetPath)
            val fileOutputStream = FileOutputStream(targetFile)
            Svg2Vector.parseSvgToXml(svgSource, fileOutputStream)
            val fg = "@android:color/white"
            try {
                updateXmlPath(targetFile, "android:strokeColor", fg)
                updateXmlPath(targetFile, "android:fillColor", fg)
                updateRootElement(targetFile, "android:tint", fg)
            } catch (e: DocumentException) {
                throw RuntimeException(e)
            }
            createAdaptive(targetFile)
        } else {
            println("Skipping file as its not svg " + svgSource.fileName)
        }
    }

    @Throws(IOException::class)
    private fun createAdaptive(xmlPath: String) {
        val foregroundXml = xmlPath.replace(".xml", "_foreground.xml")
        val foregroundFile = FileUtils.getFile(foregroundXml)
        foregroundFile.delete()
        FileUtils.moveFile(
            FileUtils.getFile(xmlPath),
            foregroundFile,
        )
        val drawableName: String = FilenameUtils.getBaseName(xmlPath)
        val resPath: String = FilenameUtils.getFullPath(xmlPath)
        createPlate(resPath)
        val document = DocumentHelper.createDocument()
        // Plain (non-adaptive) drawable so launchers like Samsung Theme Park draw it as-is
        // instead of re-theming the monochrome layer on their own plate.
        val root = document.addElement("layer-list")
            .addAttribute("xmlns:android", "http://schemas.android.com/apk/res/android")
        root.addElement("item")
            .addAttribute("android:drawable", "@drawable/$PLATE_NAME")
        // White glyph centered on the plate.
        root.addElement("item")
            .addElement("inset")
            .addAttribute("android:inset", "18%")
            .addAttribute(
                "android:drawable",
                "@drawable/" + FilenameUtils.getBaseName(foregroundXml),
            )
        XmlUtil.writeDocumentToFile(document, "$resPath$drawableName.xml")
    }

    // Shared round plate: 50% black fill with a white ring. It is a vector, so the ring
    // thickness scales with the icon size instead of being a fixed dp value.
    private fun createPlate(resPath: String) {
        val plateFile = File("$resPath$PLATE_NAME.xml")
        if (plateFile.exists()) return
        val document = DocumentHelper.createDocument()
        val root = document.addElement("vector")
            .addAttribute("xmlns:android", "http://schemas.android.com/apk/res/android")
            .addAttribute("android:width", "192dp")
            .addAttribute("android:height", "192dp")
            .addAttribute("android:viewportWidth", "192")
            .addAttribute("android:viewportHeight", "192")
        // Ring is centered on the path: outer edge = RADIUS + RING_WIDTH / 2 (must stay <= 96).
        val radius = 96.0 - RING_WIDTH / 2.0 - 1
        root.addElement("path")
            .addAttribute("android:fillColor", "#80000000")
            .addAttribute("android:strokeColor", "#FFFFFFFF")
            .addAttribute("android:strokeWidth", RING_WIDTH.toString())
            .addAttribute(
                "android:pathData",
                "M96,96m-$radius,0a$radius,$radius 0,1,1 ${radius * 2},0a$radius,$radius 0,1,1 -${radius * 2},0",
            )
        XmlUtil.writeDocumentToFile(document, plateFile.path)
    }

    private fun updateRootElement(xmlPath: String, key: String, value: String) {
        val aDocument: Document = XmlUtil.getDocument(xmlPath)
        val keyWithoutNameSpace = key.substring(key.indexOf(":") + 1)
        val attr = aDocument.rootElement.attribute(keyWithoutNameSpace)
        if (attr != null) {
            if (attr.value != "#00000000") {
                attr.value = value
            }
        } else {
            aDocument.rootElement.addAttribute(key, value)
        }
        XmlUtil.writeDocumentToFile(aDocument, xmlPath)
    }

    private fun updateXmlPath(xmlPath: String, searchKey: String, attributeValue: String) {
        val xmlDocument = XmlUtil.getDocument(xmlPath)
        val keyWithoutNameSpace = searchKey.substring(searchKey.indexOf(":") + 1)
        if (xmlDocument.rootElement != null) {
            for (e in xmlDocument.rootElement.elements("path")) {
                val attr = e.attribute(keyWithoutNameSpace)
                if (attr != null) {
                    if (attr.value != "#00000000") {
                        attr.value = attributeValue
                    }
                }
            }
            XmlUtil.writeDocumentToFile(xmlDocument, xmlPath)
        }
    }
}
