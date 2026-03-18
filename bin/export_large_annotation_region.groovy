import qupath.lib.images.writers.ome.OMEPyramidWriter
import qupath.lib.regions.RegionRequest

// ============================================================
// CONFIGURATION
// ============================================================

def env = System.getenv()

def parseBoolean = { String key, boolean defaultValue ->
    if (!env.containsKey(key)) return defaultValue
    def val = env[key]?.toString()?.trim()?.toLowerCase()
    return val in ['1', 'true', 'yes', 'y']
}

def parseInt = { String key, int defaultValue ->
    try {
        return env.containsKey(key) ? Integer.parseInt(env[key].toString().trim()) : defaultValue
    } catch (Exception ignored) {
        return defaultValue
    }
}

def parseDouble = { String key, double defaultValue ->
    try {
        return env.containsKey(key) ? Double.parseDouble(env[key].toString().trim()) : defaultValue
    } catch (Exception ignored) {
        return defaultValue
    }
}

def targetAnnotationNamesRaw = env.getOrDefault('TARGET_ANNOTATION_NAMES', 'annotation_1')
def targetAnnotationNames = targetAnnotationNamesRaw?.trim()
    ? targetAnnotationNamesRaw.split(',').collect { it.trim() }.findAll { it }
    : []

double downsample = parseDouble('DOWNSAMPLE', 1.0)

def compressionTypeName = env.getOrDefault('COMPRESSION_TYPE', 'LZW')
def compressionType
try {
    compressionType = OMEPyramidWriter.CompressionType.valueOf(compressionTypeName.toUpperCase())
} catch (Exception ignored) {
    print "Invalid COMPRESSION_TYPE='${compressionTypeName}', defaulting to LZW"
    compressionType = OMEPyramidWriter.CompressionType.LZW
}

def outputSubDir = env.getOrDefault('OUTPUT_SUBDIR', 'ExportedAnnotations')
int tileSize = parseInt('TILE_SIZE', 1024)
int nThreads = parseInt('NTHREADS', 48)
boolean bigTiff = parseBoolean('BIG_TIFF', true)
boolean buildPyramid = parseBoolean('BUILD_PYRAMID', true)

// ============================================================
// SETUP
// ============================================================

def imageName = getProjectEntry()?.getImageName() ?: '(unknown image)'
def server
try {
    server = getCurrentServer()
} catch (Exception e) {
    print "[WARN] Skipping ${imageName}: unable to open image server (${e.getMessage()})"
    return
}

def baseImageName = imageName
    .replaceAll(/(?i)\.(svs|tif|tiff|ndpi|scn|vsi|qptiff|btf|czi|lif)$/, '')

def outputPath = buildFilePath(PROJECT_BASE_DIR, outputSubDir)
mkdirs(outputPath)

print "Output directory: ${outputPath}"
print "Threads per export: ${nThreads}"
print "Compression: ${compressionType}"
print "Downsample: ${downsample}"
print "BigTIFF: ${bigTiff}"
print "Build pyramid: ${buildPyramid}"
print "Annotation filter: ${targetAnnotationNames ? targetAnnotationNames : '[ALL]'}"

// ============================================================
// COLLECT & FILTER ANNOTATIONS
// ============================================================

def allAnnotations = getAnnotationObjects()

if (allAnnotations.isEmpty()) {
    print "No annotations found. Exiting."
    return
}

def annotationsToExport = (targetAnnotationNames && !targetAnnotationNames.isEmpty())
    ? allAnnotations.findAll { a ->
        def name = a.getName()
        targetAnnotationNames.any { t -> name?.equalsIgnoreCase(t) }
    }
    : allAnnotations

if (annotationsToExport.isEmpty()) {
    print "No annotations matched: ${targetAnnotationNames}"
    print "Available: ${allAnnotations.collect { it.getName() ?: '(unnamed)' }}"
    return
}

print "Exporting ${annotationsToExport.size()} annotation(s)...\n"

// ============================================================
// EXPORT USING BUILDER
// ============================================================

def nameCount = [:]

annotationsToExport.each { annotation ->
    try {
        def roi      = annotation.getROI()
        def rawName  = annotation.getName()?.trim() ?: 'Unnamed'
        def safeName = rawName.replaceAll(/[\\/:*?"<>|]/, '_')

        def pathClass = annotation.getPathClass()
        if (pathClass != null && pathClass.toString() != rawName)
            safeName += "_${pathClass.toString().replaceAll(/[\\/:*?"<>|]/, '_')}"

        nameCount[safeName] = (nameCount[safeName] ?: 0) + 1
        def suffix   = nameCount[safeName] > 1 ? "_${nameCount[safeName]}" : ''
        def fileName = "${safeName}${suffix}.ome.tif"
        def outputFilePath = buildFilePath(outputPath, fileName)

        def request = RegionRequest.createInstance(server.getPath(), downsample, roi)

        print "  Exporting: ${fileName}"
        print "    Bounds: x=${roi.getBoundsX()}, y=${roi.getBoundsY()}, w=${roi.getBoundsWidth()}, h=${roi.getBoundsHeight()}"

        // Builder pattern — tiles are read and written in parallel internally
        def series = new OMEPyramidWriter.Builder(server)
            .region(request)
            .tileSize(tileSize)
            .compression(compressionType)
            .parallelize(nThreads)      // <-- parallelized tiled export
            .bigTiff(bigTiff)

        // Optionally write a pyramid (downsampled overview levels)
        if (buildPyramid)
            series = series.downsamples(downsample, downsample * 4, downsample * 16)

        series.build()
              .writeSeries(outputFilePath)

        print "    Saved: ${outputFilePath}"

    } catch (Exception e) {
        print "  [ERROR] ${annotation.getName()}: ${e.getMessage()}"
        e.printStackTrace()
    }
}

print "\nDone!"
