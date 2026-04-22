// List all image names in a QuPath project, one per line.
// Used by Nextflow to parallelise per-image processing.
//
// System.exit(0) is called immediately after printing so that QuPath does not
// iterate through every project image (which is the default headless behaviour
// when --image is omitted).  Without this, QuPath would run this script once
// per image, printing all names each time and exhausting memory on large projects.

def project = getProject()
if (project == null) {
    System.err.println("ERROR: No project loaded")
    System.exit(1)
}

project.getImageList().each { entry ->
    println entry.getImageName()
}

// Stop QuPath immediately — do not process remaining project images.
System.exit(0)
