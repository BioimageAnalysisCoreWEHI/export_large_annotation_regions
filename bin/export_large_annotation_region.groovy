import qupath.lib.images.writers.ome.OMEPyramidWriter
import qupath.lib.regions.RegionRequest
import qupath.lib.images.servers.AbstractTileableImageServer
import qupath.lib.images.servers.ImageServerMetadata
import qupath.lib.images.servers.TileRequest

import java.awt.image.BufferedImage
import java.awt.Color
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
int  tileSize        = parseInt('TILE_SIZE', 1024)
int  nThreads        = parseInt('NTHREADS', 48)
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
def server
try {
    server = getCurrentServer()
} catch (Exception e) {
    print "[WARN] Skipping ${imageName}: unable to open image server (${e.getMessage()})"
    return
}

def outputPath = buildFilePath(PROJECT_BASE_DIR, outputSubDir)
mkdirs(outputPath)

print "Output directory: ${outputPath}"
print "Annotation filter: ${targetAnnotationNames ? targetAnnotationNames : '[ALL]'}"
print "Channels: ${server.nChannels()} | Bit depth: ${server.getPixelType()} | Downsample: ${downsample}"

// ============================================================
// MASKED SERVER CLASS
// Wraps the original server; tiles outside the ROI shape are
// zeroed out — all channels and bit depth are preserved.
// ============================================================

class RoiMaskedServer extends AbstractTileableImageServer {

    private final def wrappedServer
    private final Shape roiShapeFullRes   // ROI shape in full-resolution coordinates
    private final int   cropX, cropY      // top-left of the bounding box (full-res)
    private final ImageServerMetadata metadata

    RoiMaskedServer(wrappedServer, roi, double downsample) {
        super()

        this.wrappedServer  = wrappedServer
        this.roiShapeFullRes = roi.getShape()

        def bounds    = roi.getBounds2D()
        this.cropX    = (int) bounds.getX()
        this.cropY    = (int) bounds.getY()
        int cropW     = (int) Math.ceil(bounds.getWidth()  / downsample)
        int cropH     = (int) Math.ceil(bounds.getHeight() / downsample)

        // Build metadata matching the cropped region exactly,
        // keeping all channel names, pixel type, pixel size, etc.
        this.metadata = new ImageServerMetadata.Builder(wrappedServer.getMetadata())
            .width(cropW)
            .height(cropH)
            .levelsFromDownsamples(1.0)   // pyramid levels added by the writer
            .build()
    }

    @Override
    ImageServerMetadata getOriginalMetadata() { return metadata }

    @Override
    String getServerType() { return "ROI Masked Server" }

    @Override
    protected BufferedImage readTile(TileRequest tileRequest) throws IOException {
        // Tile coordinates are in the *cropped* image space.
        // Convert back to full-resolution coordinates of the original server.
        double ds   = tileRequest.getDownsample()
        int tileX   = (int) (tileRequest.getTileX() * ds) + cropX
        int tileY   = (int) (tileRequest.getTileY() * ds) + cropY
        int tileW   = (int) (tileRequest.getTileWidth()  * ds)
        int tileH   = (int) (tileRequest.getTileHeight() * ds)

        def request = RegionRequest.createInstance(
            wrappedServer.getPath(), ds,
            tileX, tileY, tileW, tileH
        )

        // Read the tile from the original server (all channels, full bit depth)
        BufferedImage tile = wrappedServer.readRegion(request)
        if (tile == null) return null

        // Build a mask for this tile in tile-pixel coordinates
        // The ROI shape is in full-res coords; transform into tile space
        def at = new AffineTransform()
        at.scale(1.0 / ds, 1.0 / ds)
        at.translate(-tileX, -tileY)
        def scaledShape = at.createTransformedShape(roiShapeFullRes)

        int w = tile.getWidth()
        int h = tile.getHeight()

        def maskImg = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY)
        def g2      = maskImg.createGraphics()
        g2.setColor(Color.WHITE)
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        g2.fill(scaledShape)
        g2.dispose()

        // Zero out pixels outside the mask.
        // Works on the WritableRaster directly — preserves all channels & bit depth.
        def tileRaster = tile.getRaster()
        def maskRaster = maskImg.getRaster()
        int nBands     = tileRaster.getNumBands()
        def maskPixel  = new int[1]
        def zeroPixel  = new int[nBands]   // all zeros = background

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                maskRaster.getPixel(x, y, maskPixel)
                if (maskPixel[0] == 0) {
                    tileRaster.setPixel(x, y, zeroPixel)
                }
            }
        }

        return tile
    }

    @Override
    protected String createID() {
        return "RoiMaskedServer(" + wrappedServer.getPath() + ")"
    }

    @Override
    Collection<URI> getURIs() { return wrappedServer.getURIs() }
}

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
// EXPORT LOOP
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
        def suffix       = nameCount[safeName] > 1 ? "_${nameCount[safeName]}" : ''
        def fileName     = "${safeName}${suffix}.ome.tif"
        def outputFilePath = buildFilePath(outputPath, fileName)

        print "  Exporting: ${fileName}"
        print "    Bounds: x=${roi.getBoundsX()}, y=${roi.getBoundsY()}, w=${roi.getBoundsWidth()}, h=${roi.getBoundsHeight()}"

        // Wrap original server with the masking layer
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