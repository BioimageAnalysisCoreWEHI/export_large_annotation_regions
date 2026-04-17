// List all image names in a QuPath project, one per line.
// Used by Nextflow to parallelise per-image processing.

def project = getProject()
if (project == null) {
    System.err.println("ERROR: No project loaded")
    System.exit(1)
}

project.getImageList().each { entry ->
    println entry.getImageName()
}
