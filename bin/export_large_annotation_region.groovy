import qupath.lib.images.writers.ome.OMEPyramidWriter
import qupath.lib.regions.RegionRequest
import qupath.lib.images.servers.AbstractTileableImageServer
import qupath.lib.images.servers.ImageServerMetadata
import qupath.lib.images.servers.TileRequest

import java.awt.image.BufferedImage
import java.awt.Color
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.Shape

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
    try { return env.containsKey(key) ? Integer.parseInt(env[key].toString().trim()) : defaultValue }
    catch (Exception ignored) { return defaultValue }
}

def parseDouble = { String key, double defaultValue ->
    try { return env.containsKey(key) ? Double.parseDouble(env[key].toString().trim()) : defaultValue }
    catch (Exception ignored) { return defaultValue }
}

def targetAnnotationNamesRaw = env.getOrDefault('TARGET_ANNOTATION_NAMES', 'annotation_1')
def targetAnnotationNames = targetAnnotationNamesRaw?.trim()
    ? targetAnnotationNamesRaw.split(',').collect { it.trim() }.findAll { it }
    : []

double downsample    = parseDouble('DOWNSAMPLE', 1.0)
def outputSubDir     = env.getOrDefault('OUTPUT_SUBDIR', 'ExportedAnnotations')
int  tileSize        = parseInt('TILE_SIZE', 512)
int  nThreadsRequested = parseInt('NTHREADS', 32)
int  availableCores    = Runtime.getRuntime().availableProcessors()
int  nThreads          = Math.max(1, Math.min(nThreadsRequested, availableCores))
boolean bigTiff      = parseBoolean('BIG_TIFF', true)
boolean buildPyramid = parseBoolean('BUILD_PYRAMID', true)

def compressionTypeName = env.getOrDefault('COMPRESSION_TYPE', 'LZW')
def compressionType
try {
    compressionType = OMEPyramidWriter.CompressionType.valueOf(compressionTypeName.toUpperCase())
} catch (Exception ignored) {
    compressionType = OMEPyramidWriter.CompressionType.LZW
}

// ============================================================
// SETUP
// ============================================================

def imageName = getProjectEntry()?.getImageName() ?: '(unknown image)'

// ============================================================
// COLLECT & FILTER ANNOTATIONS (BEFORE OPENING SERVER)
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

def server
try {
    server = getCurrentServer()
} catch (Exception e) {
    print "[WARN] Skipping ${imageName}: unable to open image server (${e.getMessage()})"
    return
}

def outputPath = new File(outputSubDir).isAbsolute()
    ? outputSubDir
    : buildFilePath(PROJECT_BASE_DIR, outputSubDir)
mkdirs(outputPath)

print "Output directory: ${outputPath}"
print "Annotation filter: ${targetAnnotationNames ? targetAnnotationNames : '[ALL]'}"
print "Channels: ${server.nChannels()} | Bit depth: ${server.getPixelType()} | Downsample: ${downsample}"
print "Tile size: ${tileSize} | Threads: ${nThreads} (requested ${nThreadsRequested}, available ${availableCores}) | Pyramid: ${buildPyramid}"

print "[DEBUG] Requested annotation names (${targetAnnotationNames.size()}):"
targetAnnotationNames.eachWithIndex { n, i ->
    print "[DEBUG]   target[${i}] = '${n}'"
}

print "[DEBUG] Matched annotations (${annotationsToExport.size()}):"
annotationsToExport.eachWithIndex { a, i ->
    def r = a.getROI()
    print "[DEBUG]   match[${i}] name='${a.getName()}' class='${a.getPathClass()}' hash=${System.identityHashCode(a)} roiHash=${System.identityHashCode(r)} bounds=(x=${r.getBoundsX()}, y=${r.getBoundsY()}, w=${r.getBoundsWidth()}, h=${r.getBoundsHeight()})"
}

// ============================================================
// MASKED SERVER CLASS
// ============================================================

class RoiMaskedServer extends AbstractTileableImageServer {

    private final def wrappedServer
    private final Shape roiShapeFullRes
    private final int   cropX, cropY
    private final double baseDownsample
    private final ImageServerMetadata metadata
    private final String serverId

    RoiMaskedServer(wrappedServer, roi, double downsample) {
        super()

        this.wrappedServer    = wrappedServer
        this.roiShapeFullRes  = roi.getShape()
        this.baseDownsample   = downsample

        this.cropX  = (int) roi.getBoundsX()
        this.cropY  = (int) roi.getBoundsY()
        int cropW   = (int) Math.ceil(roi.getBoundsWidth()  / downsample)
        int cropH   = (int) Math.ceil(roi.getBoundsHeight() / downsample)

        this.serverId = "RoiMaskedServer(" + wrappedServer.getPath() +
            "|x=" + this.cropX +
            "|y=" + this.cropY +
            "|w=" + cropW +
            "|h=" + cropH +
            "|ds=" + this.baseDownsample + ")"

        this.metadata = new ImageServerMetadata.Builder(wrappedServer.getMetadata())
            .width(cropW)
            .height(cropH)
            .levelsFromDownsamples(1.0)
            .build()
    }

    @Override
    ImageServerMetadata getOriginalMetadata() { return metadata }

    @Override
    String getServerType() { return "ROI Masked Server" }

    @Override
    qupath.lib.images.servers.ImageServerBuilder.ServerBuilder createServerBuilder() {
        return null
    }

    @Override
    protected BufferedImage readTile(TileRequest tileRequest) throws IOException {

        double levelDownsample  = tileRequest.getDownsample()
        double sourceDownsample = this.baseDownsample * levelDownsample

        int tileX = (int) Math.floor(cropX + tileRequest.getTileX() * sourceDownsample)
        int tileY = (int) Math.floor(cropY + tileRequest.getTileY() * sourceDownsample)
        int tileW = (int) Math.ceil(tileRequest.getTileWidth()  * sourceDownsample)
        int tileH = (int) Math.ceil(tileRequest.getTileHeight() * sourceDownsample)

        def request = RegionRequest.createInstance(
            wrappedServer.getPath(), sourceDownsample, tileX, tileY, tileW, tileH
        )

        BufferedImage tile = wrappedServer.readRegion(request)
        if (tile == null) return null

        int w = tile.getWidth()
        int h = tile.getHeight()
        int nBands = tile.getRaster().getNumBands()

        // Transform ROI shape into this tile's pixel coordinate space
        def at = new AffineTransform()
        at.scale(1.0 / sourceDownsample, 1.0 / sourceDownsample)
        at.translate(-tileX, -tileY)
        def scaledShape = at.createTransformedShape(roiShapeFullRes)

        def tileBounds = new Rectangle(0, 0, w, h)
        int[] zeroRow = new int[w]   // reused zero row to avoid large per-tile allocations

        // ── Fast path 1: entire tile is inside the ROI ──────────────────────
        // contains(Rectangle) checks all four corners + boundary — if the
        // scaled ROI shape fully contains the tile bounding box, no pixel
        // outside the ROI exists in this tile, so return immediately.
        if (scaledShape.contains(tileBounds)) {
            return tile
        }

        def tileRaster = tile.getRaster()

        // ── Fast path 2: entire tile is outside the ROI ─────────────────────
        // intersects() returns false when the shape and rectangle are fully
        // disjoint. Zero row-by-row to avoid allocating huge w*h arrays per tile.
        if (!scaledShape.intersects(tileBounds)) {
            for (int b = 0; b < nBands; b++) {
                for (int y = 0; y < h; y++) {
                    tileRaster.setSamples(0, y, w, 1, b, zeroRow)
                }
            }
            return tile
        }

        // ── Boundary tile: render mask and zero outside runs ─────────────────
        // Only tiles that straddle the ROI edge reach this point.
        def maskImg = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY)
        def g2      = maskImg.createGraphics()
        g2.setColor(Color.WHITE)
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        g2.fill(scaledShape)
        g2.dispose()

        def maskRaster = maskImg.getRaster()

        // Row-wise run-length zeroing — batches contiguous outside-ROI runs
        // into a single setSamples() call per band, avoiding per-pixel overhead.
        int[] maskRow = new int[w]

        for (int y = 0; y < h; y++) {
            maskRaster.getSamples(0, y, w, 1, 0, maskRow)
            int x = 0
            while (x < w) {
                // Skip pixels inside the ROI (mask == 1)
                while (x < w && maskRow[x] != 0) x++
                int runStart = x
                // Accumulate pixels outside the ROI (mask == 0)
                while (x < w && maskRow[x] == 0) x++
                int runLen = x - runStart
                if (runLen > 0) {
                    for (int b = 0; b < nBands; b++) {
                        tileRaster.setSamples(runStart, y, runLen, 1, b, zeroRow)
                    }
                }
            }
        }

        return tile
    }

    @Override
    protected String createID() {
        return serverId
    }

    @Override
    Collection<URI> getURIs() { return wrappedServer.getURIs() }
}

// ============================================================
// EXPORT LOOP
// ============================================================

print "Exporting ${annotationsToExport.size()} annotation(s)...\n"

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
        def suffix       = nameCount[safeName] > 1 ? "_${nameCount[safeName]}" : ''
        def fileName     = "${safeName}${suffix}.ome.tif"
        def outputFilePath = buildFilePath(outputPath, fileName)

        print "  Exporting: ${fileName}"
        print "    Bounds: x=${roi.getBoundsX()}, y=${roi.getBoundsY()}, w=${roi.getBoundsWidth()}, h=${roi.getBoundsHeight()}"
        print "    [DEBUG] Annotation='${rawName}' class='${pathClass}' annHash=${System.identityHashCode(annotation)} roiHash=${System.identityHashCode(roi)}"

        def maskedServer = new RoiMaskedServer(server, roi, downsample)

        def series = new OMEPyramidWriter.Builder(maskedServer)
            .tileSize(tileSize)
            .compression(compressionType)
            .parallelize(nThreads)
            .bigTiff(bigTiff)

        if (buildPyramid)
            series = series.downsamples(1.0, 4.0, 16.0)

        series.build().writeSeries(outputFilePath)
        maskedServer.close()

        print "    Saved: ${outputFilePath}"

    } catch (Exception e) {
        print "  [ERROR] ${annotation.getName()}: ${e.getMessage()}"
        e.printStackTrace()
    }
}

print "\nDone!"