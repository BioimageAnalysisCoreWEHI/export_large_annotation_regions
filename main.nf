nextflow.enable.dsl = 2

params.project = null
params.qupath_bin = null
params.script = "${projectDir}/bin/export_large_annotation_region.groovy"
params.target_annotation_names = "annotation_1"
params.downsample = 1.0
params.compression_type = "LZW"
params.output_subdir = "ExportedAnnotations"
params.tile_size = 512
params.n_threads = 48
params.big_tiff = true
params.build_pyramid = true
params.outdir = "results"
params.publish_dir_mode = "copy"
params.validate_params = true

process EXPORT_LARGE_ANNOTATION_REGIONS {
  tag "${project_path}"
    label 'process_heavy'

    publishDir "${params.outdir}", mode: params.publish_dir_mode

    input:
    tuple val(project_path), val(qupath_bin), val(script_path), val(target_annotation_names), val(downsample), val(compression_type), val(output_subdir), val(tile_size), val(n_threads), val(big_tiff), val(build_pyramid)

    output:
    path "ExportedAnnotations"
    path "qupath_large_annotation_export.log"

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

    project_dir="\$(dirname "${project_path}")"
    project_export_dir="\${project_dir}/${output_subdir}"

    export TARGET_ANNOTATION_NAMES="${target_annotation_names}"
    export DOWNSAMPLE="${downsample}"
    export COMPRESSION_TYPE="${compression_type}"
    export OUTPUT_SUBDIR="${output_subdir}"
    export TILE_SIZE="${tile_size}"
    export NTHREADS="${n_threads}"
    export BIG_TIFF="${big_tiff}"
    export BUILD_PYRAMID="${build_pyramid}"

    "${qupath_bin}" script "${script_path}" --project "${project_path}" \
      2>&1 | tee qupath_large_annotation_export.log

    mkdir -p ExportedAnnotations

    if [[ -d "\${project_export_dir}" ]]; then
      cp -r "\${project_export_dir}/." ExportedAnnotations/
    else
      echo "ERROR: Expected output directory not found: \${project_export_dir}" >&2
      exit 1
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

    def targetAnnotationNamesParam = params.get('target_annotation_names', 'annotation_1').toString()
    def downsampleParam = params.get('downsample', 1.0) as double
    def compressionTypeParam = params.get('compression_type', 'LZW').toString()
    def outputSubdirParam = params.get('output_subdir', 'ExportedAnnotations').toString()
    def tileSizeParam = params.get('tile_size', 512) as int
    def nThreadsParam = params.get('n_threads', 48) as int
    def bigTiffParam = params.get('big_tiff', true) as boolean
    def buildPyramidParam = params.get('build_pyramid', true) as boolean

    if (downsampleParam <= 0) {
      error "downsample must be > 0"
    }
    if (tileSizeParam <= 0) {
      error "tile_size must be > 0"
    }
    if (nThreadsParam <= 0) {
      error "n_threads must be > 0"
    }
    if (!outputSubdirParam?.trim()) {
      error "output_subdir cannot be empty"
    }

    channel
        .of(tuple(
            projectFile.toString(),
            qupathExe.toString(),
            scriptFile.toString(),
            targetAnnotationNamesParam,
            downsampleParam,
            compressionTypeParam,
            outputSubdirParam,
            tileSizeParam,
            nThreadsParam,
            bigTiffParam,
            buildPyramidParam
        ))
        | EXPORT_LARGE_ANNOTATION_REGIONS
}
