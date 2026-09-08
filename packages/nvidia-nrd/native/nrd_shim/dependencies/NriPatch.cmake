set(CAUSTICA_NRI_LIFETIME_PATCH "${CMAKE_CURRENT_LIST_DIR}/nri-destroy-lifetime.patch")

function(require_nri_patch source)
    execute_process(COMMAND git -C "${source}" apply --reverse --check "${CAUSTICA_NRI_LIFETIME_PATCH}"
        RESULT_VARIABLE result ERROR_QUIET)
    if(NOT result EQUAL 0)
        message(FATAL_ERROR "NRI lifetime patch is missing. Run prepareNrdNativeDependencies.")
    endif()
endfunction()

function(prepare_nri_patch source)
    execute_process(COMMAND git -C "${source}" apply --reverse --check "${CAUSTICA_NRI_LIFETIME_PATCH}"
        RESULT_VARIABLE applied ERROR_QUIET)
    if(applied EQUAL 0)
        return()
    endif()
    # Git validates the entire patch before writing; conflicting local edits are left intact.
    execute_process(COMMAND git -C "${source}" apply "${CAUSTICA_NRI_LIFETIME_PATCH}"
        COMMAND_ERROR_IS_FATAL ANY)
endfunction()
