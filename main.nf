nextflow.enable.dsl = 2

params.project = null
params.qupath_bin = "/stornext/System/data/software/rhel/9/base/tools/QuPath/0.6.0/bin/QuPath"
params.script = "${projectDir}/bin/export_large_annotation_region.groovy"
params.list_images_script = "${projectDir}/bin/list_project_images.groovy"
params.target_annotation_names = "annotation_1"
params.downsample = 1.0
params.compression_type = "LZW"
params.tile_size = 512
params.num_cpus = 36
params.big_tiff = true
params.build_pyramid = true
params.outdir = "results"
params.publish_dir_mode = "copy"
params.validate_params = true

/*
 * List all image names in a QuPath project.
 * Stdout is one image name per line, consumed by splitText downstream.
 */
process LIST_IMAGES {
    tag "${project_path}"
    label 'process_light'

    input:
    tuple val(project_path), val(qupath_bin), val(list_script)

    output:
    stdout

    script:
    """
    set -euo pipefail
    "${qupath_bin}" script "${list_script}" --project "${project_path}" 2>/dev/null \
      | grep -iE '\\.(tiff?|ome\\.tif|svs|ndpi|qptiff|czi|lif|vsi|scn|btf)\$'
    """
}

/*
 * Export annotations for a single image.
 * Each image is processed as a separate Nextflow task, enabling parallelism.
 */
process EXPORT_LARGE_ANNOTATION_REGIONS {
    tag "${image_name}"
    label 'process_heavy'

  publishDir "${params.outdir}/ExportedAnnotations", mode: params.publish_dir_mode, pattern: "*.ome.tif"
  publishDir "${params.outdir}", mode: params.publish_dir_mode, pattern: "qupath_large_annotation_export_*.log"

    input:
    tuple val(project_path), val(qupath_bin), val(script_path), val(image_name), val(target_annotation_names), val(downsample), val(compression_type), val(tile_size), val(num_cpus), val(big_tiff), val(build_pyramid)

    output:
    path "*.ome.tif", optional: true
    path "qupath_large_annotation_export_${image_name}.log"

    script:
    """
    set -euo pipefail

    if [[ ! -f "${project_path}" ]]; then
      echo "ERROR: QuPath project not found: ${project_path}" >&2
      exit 1
    fi

    if [[ ! -x "${qupath_bin}" ]]; then
      echo "ERROR: QuPath binary is not executable: ${qupath_bin}" >&2
      exit 1
    fi

    if [[ ! -f "${script_path}" ]]; then
      echo "ERROR: Groovy script not found: ${script_path}" >&2
      exit 1
    fi

    export TARGET_ANNOTATION_NAMES="${target_annotation_names}"
    export DOWNSAMPLE="${downsample}"
    export COMPRESSION_TYPE="${compression_type}"
    export OUTPUT_DIR="\${PWD}"
    export TILE_SIZE="${tile_size}"
    export NTHREADS="${num_cpus}"
    export BIG_TIFF="${big_tiff}"
    export BUILD_PYRAMID="${build_pyramid}"

    # Disable exit-on-error around the QuPath call so we can inspect the log
    # before deciding whether a non-zero exit is a real failure or a broken URI.
    set +e
    "${qupath_bin}" script "${script_path}" \
      --project "${project_path}" \
      --image "${image_name}" \
      2>&1 | tee "qupath_large_annotation_export_${image_name}.log"
    QUPATH_EXIT=\${PIPESTATUS[0]}
    set -e

    if [[ \$QUPATH_EXIT -ne 0 ]]; then
      if grep -q "Failed to load ImageServer" "qupath_large_annotation_export_${image_name}.log"; then
        echo "[WARN] Skipping ${image_name}: broken image URI in QuPath project (ImageServer could not be opened)" >&2
        exit 0
      fi
      echo "[ERROR] QuPath failed for ${image_name} (exit \${QUPATH_EXIT})" >&2
      exit \$QUPATH_EXIT
    fi
    """
}

workflow {
    if (!params.project) {
        error "Missing required parameter: --project"
    }
    if (!params.qupath_bin) {
        error "Missing required parameter: --qupath_bin"
    }

    def projectFile = file(params.project)
    if (!projectFile.exists()) {
        error "Project file does not exist: ${params.project}"
    }

    def qupathExe = file(params.qupath_bin)
    if (!qupathExe.exists()) {
        error "QuPath binary does not exist: ${params.qupath_bin}"
    }

    def scriptParam = params.script.toString()
    def scriptCandidates = [
        file(scriptParam),
        file("${projectDir}/${scriptParam}")
    ]
    def scriptFile = scriptCandidates.find { candidate -> candidate.exists() }
    if (!scriptFile) {
        error "Groovy script does not exist: ${params.script} (tried: ${scriptCandidates*.toString().join(', ')})"
    }

    def listScriptParam = params.list_images_script.toString()
    def listScriptCandidates = [
        file(listScriptParam),
        file("${projectDir}/${listScriptParam}")
    ]
    def listScriptFile = listScriptCandidates.find { candidate -> candidate.exists() }
    if (!listScriptFile) {
        error "List-images script does not exist: ${params.list_images_script} (tried: ${listScriptCandidates*.toString().join(', ')})"
    }

    def targetAnnotationNamesParam = params.get('target_annotation_names', 'annotation_1').toString()
    def downsampleParam = params.get('downsample', 1.0) as double
    def compressionTypeParam = params.get('compression_type', 'LZW').toString()
    def tileSizeParam = params.get('tile_size', 512) as int
    def numCpusParam = params.get('num_cpus', 36) as int
    def bigTiffParam = params.get('big_tiff', true) as boolean
    def buildPyramidParam = params.get('build_pyramid', true) as boolean

    if (downsampleParam <= 0) {
      error "downsample must be > 0"
    }
    if (tileSizeParam <= 0) {
      error "tile_size must be > 0"
    }
    if (numCpusParam <= 0) {
      error "num_cpus must be > 0"
    }

    // Step 1 — discover images in the project
    def list_input = channel.of(
        tuple(
            projectFile.toString(),
            qupathExe.toString(),
            listScriptFile.toString()
        )
    )

    image_names = LIST_IMAGES(list_input)
        .splitText()
        .map { it.trim() }
        .filter { it }
        .distinct()  // guard against duplicate names if QuPath emits multiple lines per image

    // Step 2 — fan out: one EXPORT task per image
    export_input = image_names.map { img_name ->
        tuple(
            projectFile.toString(),
            qupathExe.toString(),
            scriptFile.toString(),
            img_name,
            targetAnnotationNamesParam,
            downsampleParam,
            compressionTypeParam,
            tileSizeParam,
            numCpusParam,
            bigTiffParam,
            buildPyramidParam
        )
    }

    EXPORT_LARGE_ANNOTATION_REGIONS(export_input)
}
