

import org.freeplane.api.MindMap
import org.freeplane.core.ui.CaseSensitiveFileNameExtensionFilter
import org.freeplane.core.util.LogUtils
import org.freeplane.features.map.clipboard.MapClipboardController
import org.freeplane.features.map.MapModel
import org.freeplane.features.map.MapWriter.Mode
import org.freeplane.features.mode.Controller

import javax.swing.*
import java.nio.charset.StandardCharsets
import java.text.MessageFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private byte[] getZipBytes(Map fileToPathInZipMap, File mapFile, byte[] mapBytes) {
    def byteArrayOutputStream = new ByteArrayOutputStream()
    ZipOutputStream zipOutput = new ZipOutputStream(byteArrayOutputStream)

    // Add each file to the ZIP output stream.
    fileToPathInZipMap.each { file, path ->
        zipOutput = addZipEntry(zipOutput, file, path)
    }

    // Add the map itself as an entry.
    logger.info("zipMap: added ${mapFile.name}")
    ZipEntry entry = new ZipEntry(mapFile.name)
    entry.time = mapFile.lastModified()
    zipOutput.putNextEntry(entry)
    zipOutput.write(mapBytes)
    zipOutput.closeEntry()
    
    zipOutput.close()
    return byteArrayOutputStream.toByteArray()
}

private ZipOutputStream addZipEntry(ZipOutputStream zipOutput, File file, String path) {
    if (file.isDirectory() && !path.endsWith('/')) {
        path += "/"
        logger.info("zipMap: added directory $path")
        ZipEntry entry = new ZipEntry(path)
        entry.time = file.lastModified()
        zipOutput.putNextEntry(entry)
        zipOutput.closeEntry()
    } else if (file.isFile()) {
        logger.info("zipMap: adding file $file to $path")
        ZipEntry entry = new ZipEntry(path)
        entry.time = file.lastModified()
        zipOutput.putNextEntry(entry)

        // Stream the file content into the ZIP output stream.
        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            byte[] buffer = new byte[1024]
            int length
            while ((length = fileInputStream.read(buffer)) >= 0) {
                zipOutput.write(buffer, 0, length)
            }
        } catch (IOException e) {
            logger.error("Error adding ${file.name} to ZIP: ${e.message}")
        } finally {
            zipOutput.closeEntry()
        }
    }
    
    return zipOutput
}

private String getPathInZip(File file, String dependenciesDir, Map fileToPathInZipMap) {
    def mappedPath = fileToPathInZipMap[file]
    if (mappedPath) return mappedPath
    
    def path = "${dependenciesDir}/${file.name}"
    if (file.isDirectory()) path += '/'

    while (contains(fileToPathInZipMap.values(), path)) {
        path = path.replaceFirst('(\\.\\w+)?$', '1$1')
    }
    
    logger.info("zipMap: mapped $file to $path")
    return path
}

static boolean contains(Collection collection, String path) {
    return collection.contains(path)
}

private static byte[] getBytes(MapModel map) {
    StringWriter stringWriter = new StringWriter(4 * 1024)
    BufferedWriter out = new BufferedWriter(stringWriter)

    def mapWriter = Controller.getCurrentModeController().getMapController().getMapWriter()

    try {
        mapWriter.writeMapAsXml(map, out, Mode.FILE, MapClipboardController.CopiedNodeSet.ALL_NODES, false)
    } catch (MissingMethodException) {
        mapWriter.writeMapAsXml(map, out, Mode.FILE, true, false)
    }

    return stringWriter.toString().getBytes(StandardCharsets.UTF_8)
}

private boolean confirmOverwrite(File file) {
    def title = getText('Create zip file')
    def question = textUtils.format('file_already_exists', file)

    int selection = JOptionPane.showConfirmDialog(ui.frame, question, title, JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE)
    
    return selection == JOptionPane.YES_OPTION
}

private File askForZipFile(File zipFile) {
    def zipFileFilter = new CaseSensitiveFileNameExtensionFilter('zip', 'ZIP files')

    def chooser = new JFileChooser(fileSelectionMode: JFileChooser.FILES_ONLY, fileFilter: zipFileFilter, selectedFile: zipFile)

    if (chooser.showSaveDialog() == JFileChooser.APPROVE_OPTION) {
        if (!chooser.selectedFile.exists() || confirmOverwrite(chooser.selectedFile)) 
            return chooser.selectedFile
        
        return null
    }
}

/**
 * It is used to collect unique file paths.
 * Canonical, so that ../ and ./ do not lead to different paths for the same file.
 * @return canonical file for the URI
 */
static File getUriAsCanonicalFile(File mapDir, URI uri) {
    try {
        if (uri == null) return null
        
        def scheme = uri.scheme
        
        if (scheme == null || scheme == 'file') {
            def path = uri.path ?: uri.schemeSpecificPart
            def file = new File(path)
            return file.absolute ? file.canonicalFile : new File(mapDir, path).canonicalFile
        }

        return new File(uri).canonicalFile
    } catch (Exception e) {
        LogUtils.info("link is not a file uri: $e")
        return null
    }
}

// Searches the map for file references that have to be mapped to a file in the ZIP.
private Map createFileToPathInZipMap(MindMap newMindMap, String dependenciesDir) {
    File mapDir = node.mindMap.file.parentFile

    // Closure for handling HTML text in nodes.
    def handleHtmlText = { String text, Map map ->
        if (!text) return text
        
        def links = ~/(href|src)=(["'])(.+)\2/
        
        def m = links.matcher(text)

        if (m.find()) {
            def buffer = new StringBuffer()
            for (;;) {
                def ref = m.group(3)
                def xpath = getMappedPath(ref, map, mapDir, dependenciesDir)

                if (xpath) {
                    logger.info("patching inline reference ${m.group(0)}")
                    m.appendReplacement(buffer,"${m.group(1)}=${m.group(2)}${xpath}${m.group(2)}")
                } else {
                    m.appendReplacement(buffer,m.group(0))
                }

                if (!m.find()) break
            }

            m.appendTail(buffer)
            return buffer.toString()
        }
        
        return text
    }

    // Create a mapping of files to their paths in the ZIP.
    def fileToPathInZipMap = newMindMap.root.findAll().inject(new LinkedHashMap()) { map,node ->
        
        def path

        // == link
        path = getMappedPath(node.link.uri,map,mapDir,dependenciesDir)

        if (path) node.link.text=path

        // == external object
        path=getMappedPath(node.externalObject.uri,map,mapDir,dependenciesDir)

        if(path){
            node.externalObject.uri=URI.create(path)

            // == attributes 
            def attributes=node.attributes
            
            attributes.eachWithIndex{ value,i ->
                if(value instanceof URI){
                    path=getMappedPath(value,map,mapDir,dependenciesDir)

                    if(path){
                        attributes.set(i,new URI(path))
                    }
                }
            }

            // Handle text fields for HTML content.
            def nodeText=node.text
            
            if(htmlUtils.isHtmlNode(nodeText)){
                node.text=handleHtmlText(nodeText,map)
            }

            // Handle details and notes similarly.
            def detailsText=node.detailsText
            
            if(detailsText){
                node.detailsText=handleHtmlText(detailsText,map)
            }

            def noteText=node.noteText
            
            if(noteText){
                node.noteText=handleHtmlText(noteText,map)
            }
            
            return map 
            
         }

         return fileToPathInZipMap 
     }
     return fileToPathInZipMap // Ensure we return this mapping for further use.
}

private String getMappedPath(Object uriObject, Map fileToPathInZipMap, File mapDir,String dependenciesDir){
     if(!uriObject)return null

     URI uri=(uriObject instanceof URI)?uriObject:new URI(uriObject.toString())
     def f=getUriAsCanonicalFile(mapDir,uri)

     if(f!=null && f.exists()){
         def path=getPathInZip(f,dependenciesDir,fileToPathInZipMap)

         fileToPathInZipMap[f]=path

         path=urlEncode(path)

         return uri.rawFragment?path+'#'+uri.rawFragment:path 
     }

     return null 
}

private static urlEncode(String string){
     def uri=new URI(null,string,null)

     return uri.rawPath 
}

private static String getText(String key,Object... parameters){
     if(parameters)return MessageFormat.format(key ,parameters)

     return key 
}

// Main function to trigger zipping of the mind map.
boolean zipMindMap(File file){
     if(file==null){
         ui.errorMessage(getText('You have to save this map first'))
         return false 
     }

     // Check and prompt for saving the mindmap.
     if(!node.mindMap.isSaved()){
         def question=getText('Do you want to save {0} first?',node.mindMap.name)
         final int selection=JOptionPane.showConfirmDialog(ui.frame ,question,getText('Create zip file'),JOptionPane.YES_NO_CANCEL_OPTION,JOptionPane.QUESTION_MESSAGE)

         if(selection==JOptionPane.YES_OPTION)node.mindMap.save(false)
         else if(selection==JOptionPane.CANCEL_OPTION)return false 
     }

     // Prepare for zipping.
     def baseName=file.name.replaceFirst('\\.mm','')
     def zipFile=askForZipFile(new File(file.parentFile ,baseName+'.zip'))
     
     if(zipFile==null)return false 

     MindMap newMindMap=c.mapLoader(file).unsetMapLocation().mindMap 

     // Ensure mindmap is valid before proceeding.
     if(newMindMap==null){
         ui.errorMessage(getText('Cannot create a copy of {0}',file))
         return false 
     }

     // Create mapping and write bytes to ZIP.
     def dependenciesDir="${baseName}-files"
     Map fileToPathInZipMap=createFileToPathInZipMap(newMindMap,dependenciesDir)

     // Add the map itself as an entry in ZIP.
     byte[] bytes=getBytes(newMindMap.delegate)
     byte[] zipBytes=getZipBytes(fileToPathInZipMap,file,bytes)

     try (FileOutputStream fos=new FileOutputStream(zipFile)){
         fos.write(zipBytes)
         logger.info("zipMindmap: wrote ${zipFile.absolutePath}")
         c.statusInfo=getText('wrote {0}',zipFile.absolutePath)
     } catch(IOException e){
         ui.errorMessage(getText('Error writing ZIP file: {0}', e.message))
         return false 
     }

     return true 
}

// Triggering the main zipping process.
zipMindMap(node.mindMap.file)