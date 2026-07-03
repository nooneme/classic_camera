package com.classic.camera

/**
 * 一个物理镜头的静态探测结果。
 *
 * 探测阶段（首次启动）遍历所有支持 RAW 的物理镜头，把 CameraCharacteristics 里
 * 白名单字段的值收集到这里，存本地供拍照界面使用。
 *
 * 强类型字段：拍照界面/排序/按钮文案直接用的数值。
 * 字符串字段：复杂对象（色彩矩阵、黑电平等）原样存 toString()，
 *             后续 RAW→DNG/处理管线阶段需要时再写解析器。
 *
 * @param logicalCameraId    打开相机时调 openCamera() 的镜头 ID（逻辑镜头或独立镜头）
 * @param physicalCameraId   目标物理镜头 ID；null 表示独立镜头
 * @param label              调试/日志展示用
 * @param focalLength        availableFocalLengths[0]，按钮文案与排序依据（mm）
 * @param aperture           availableApertures[0]
 * @param lensFacing         镜头朝向：0=前置,1=后置,2=外接
 * @param pixelArraySize     "4080x3060"
 * @param activeArraySize    Rect 文本，如 "Rect(0, 0 - 4080, 3060)"
 * @param colorFilterArrangement  CFA 排列（0=RGGB,1=GRBG,2=GBRG,3=BGGR）
 * @param rawMaxSize         从 streamConfigurationMap 解析出的最大 RAW_SENSOR 尺寸，如 "4080x3060"
 * @param whiteLevel         静态白电平（饱和值）
 * @param referenceIlluminant1/2  colorTransform1/2 对应的参考光源编号
 * @param sensorOrientation   传感器物理安装角度：0/90/180/270，UV 旋转用
 */
data class LensInfo(
    val logicalCameraId: String,
    val physicalCameraId: String?,
    val label: String,
    val focalLength: Float,
    val aperture: Float,
    val lensFacing: Int,
    val pixelArraySize: String,
    val activeArraySize: String,
    val colorFilterArrangement: Int,
    val rawMaxSize: String,
    val whiteLevel: Int,
    val referenceIlluminant1: Int,
    val referenceIlluminant2: Int,
    val blackLevelPattern: String,
    val calibrationTransform1: String,
    val calibrationTransform2: String,
    val colorTransform1: String,
    val colorTransform2: String,
    val forwardMatrix1: String,
    val forwardMatrix2: String,
    val sensorOrientation: Int,

    /** 曝光时间范围（纳秒），来自 SENSOR_INFO_EXPOSURE_TIME_RANGE。 */
    val exposureTimeMinNs: Long = 100_000L,
    val exposureTimeMaxNs: Long = 1_000_000_000L,

    /** 感光度范围，来自 SENSOR_INFO_SENSITIVITY_RANGE。 */
    val sensitivityMin: Int = 100,
    val sensitivityMax: Int = 12_800,

    /** 传感器物理尺寸（mm），来自 SENSOR_INFO_PHYSICAL_SIZE，用于算 35mm 等效焦距。 */
    val sensorWidthMm: Float = 0f,
    val sensorHeightMm: Float = 0f
)
