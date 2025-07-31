package co.steale.shaderlabs

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.steale.shaderlabs.ui.theme.ShaderLabsTheme
import org.intellij.lang.annotations.Language

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShaderLabsTheme {
                SimpleShader()
            }
        }
    }
}

@Language("AGSL")
const val SHADER_SRC = """
uniform float2 iResolution;   // layer size in px
uniform shader content;       // original composable content
uniform float  radius;        // normalized (0..1), e.g., 0.3
uniform float2 center;        // normalized (0..1), e.g., (0.5, 0.5)
uniform float  ior;           // Index of Refraction, e.g., 1.33 for water/glass
uniform float  highlightStrength; // Intensity of the specular highlight
uniform float  bevelWidth;    // Normalized width of the bevel highlight band
uniform float  thickness;     // Perceived thickness of the glass for distortion scaling
uniform float  shadowIntensity; // Intensity of the cast shadow
uniform float  chromaticAberrationStrength; // Strength of chromatic aberration effect
uniform float  frostedGlassBlurRadius; // Radius for the frosted glass blur effect

const float PI = 3.14159265359;
// Light direction for casting the shadow (from top-right-front)
const vec3 LIGHT_DIR_SHADOW = normalize(vec3(0.5, 0.5, 1.0));
// Light direction for the specular highlight (from top-right-front)
const vec3 LIGHT_DIR_HIGHLIGHT = normalize(vec3(0.5, 0.5, 1.0));
// Viewer direction (looking straight down onto the surface)
const vec3 VIEW_DIR = vec3(0.0, 0.0, -1.0);

// Helper function for blurring content samples (used for frosted glass)
half4 getBlurredColor(float2 coord, float blur_radius_px) {
    if (blur_radius_px <= 0.0) {
        return content.eval(coord); // No blur if radius is zero or less
    }

    // Number of samples per dimension for the box blur (e.g., 2 means a 5x5 grid)
    const int num_samples_per_dim = 12; // Changed to const int
    float step_size = blur_radius_px / float(num_samples_per_dim); // Distance between samples

    half4 sum_color = half4(0.0);
    float total_weight = 0.0;

    // Loop through a grid of samples around the given coordinate
    for (int i = -num_samples_per_dim; i <= num_samples_per_dim; i++) {
        for (int j = -num_samples_per_dim; j <= num_samples_per_dim; j++) {
            float2 offset = float2(float(i), float(j)) * step_size;
            sum_color += content.eval(coord + offset); // Sample content at offset
            total_weight += 1.0;
        }
    }
    return sum_color / total_weight; // Return averaged color
}

half4 main(float2 fragCoord) {
    // Convert fragment coordinate to normalized UV (0..1)
    float2 uv = fragCoord / iResolution;

    // Calculate position relative to the circle's center, and aspect-correct it
    // This ensures the circle appears round on non-square surfaces.
    float2 p = uv - center;
    p.x *= iResolution.x / iResolution.y;

    // Calculate the distance from the center of the circle (in aspect-corrected space)
    float d = length(p);

    // --- Shadow Calculation ---
    // Calculate the 2D light direction for the shadow, aspect-corrected
    float2 shadow_light_dir_2d = normalize(LIGHT_DIR_SHADOW.xy);
    shadow_light_dir_2d.x *= iResolution.x / iResolution.y;

    // Determine the shadow offset based on light direction and a fixed magnitude
    float shadow_offset_magnitude = 0.04;
    float2 shadowOffset_p_space = -shadow_light_dir_2d * shadow_offset_magnitude;

    // Calculate the distance from the center of the shadow circle
    float shadowDist = length(p - shadowOffset_p_space) - radius;

    // Smoothly apply the shadow based on its distance and intensity
    float shadowAmount = smoothstep(0.0, 0.15, shadowDist) * shadowIntensity; // 0.15 is softness
    half4 bgColor = content.eval(fragCoord); // Original background color
    half4 shadowColor = half4(0.0, 0.0, 0.0, bgColor.a); // Black shadow
    half4 backgroundWithShadow = mix(bgColor, shadowColor, shadowAmount);

    half4 finalColor;

    // --- Glass Effect Calculation (only inside the circle) ---
    if (d < radius) {
        float inner_radius = radius - bevelWidth;

        // Determine the 3D normal vector based on position within the circle:
        // Flat normal for the center, and a smoothly rounded normal for the bevel.
        vec3 normal_3d;
        if (d < inner_radius) {
            normal_3d = vec3(0.0, 0.0, 1.0); // Flat normal (pointing straight out of screen)
        } else {
            // Bevel region: smooth transition from flat to angled normal
            float t = (d - inner_radius) / bevelWidth;
            t = clamp(t, 0.0, 1.0); // 't' goes from 0 (inner bevel) to 1 (outer bevel)

            // Use trigonometric functions to create a smooth, quarter-circle profile for the normal
            float z_comp = cos(t * PI * 0.5); // Z component goes from 1 (flat) to 0 (horizontal)
            vec2 xy_comp = normalize(p) * sin(t * PI * 0.5); // XY component scales radially
            normal_3d = normalize(vec3(xy_comp, z_comp));
        }

        // Calculate the cosine of the angle of incidence between the view direction and the normal
        float cos_theta_i = dot(-VIEW_DIR, normal_3d);

        // Ensure the normal always points towards the view direction for correct refraction/reflection
        if (cos_theta_i < 0.0) {
            normal_3d = -normal_3d;
            cos_theta_i = dot(-VIEW_DIR, normal_3d);
        }

        // Refraction calculation using Snell's Law
        float eta = 1.0 / ior; // Ratio of refractive indices (air to glass)
        float k = 1.0 - eta * eta * (1.0 - cos_theta_i * cos_theta_i);
        vec3 refracted_ray_3d = (k < 0.0) ? vec3(0.0) : eta * VIEW_DIR + (eta * cos_theta_i - sqrt(k)) * normal_3d;
        refracted_ray_3d = normalize(refracted_ray_3d);

        // Reflection calculation using the built-in 'reflect' function
        vec3 reflected_ray_3d = reflect(VIEW_DIR, normal_3d);

        // Fresnel term (Schlick's approximation) to blend between refraction and reflection
        // Glass appears more reflective at grazing angles.
        float R0 = ((1.0 - ior) / (1.0 + ior));
        R0 *= R0; // Reflectance at normal incidence
        float fresnel_term = R0 + (1.0 - R0) * pow((1.0 - cos_theta_i), 5.0);

        // Calculate distorted coordinates for sampling the background content for refraction.
        // Distortion is scaled by 'thickness', 'iResolution.y', and 'normal_3d.z' for perspective.
        // 'distortion_factor' ensures distortion is primarily at the bevel.
        float distortion_factor = smoothstep(inner_radius, radius, d); // 0 in center, 1 at edge
        float2 distorted_fragCoord_refract = fragCoord + refracted_ray_3d.xy * thickness * iResolution.y / max(0.001, normal_3d.z) * distortion_factor;

        half4 refracted_color;
        // Apply chromatic aberration if strength is greater than 0
        if (chromaticAberrationStrength > 0.0) {
            float2 ab_offset_px = refracted_ray_3d.xy * chromaticAberrationStrength * iResolution.y; // Pixel offset based on ray direction

            // Sample content for R, G, B channels with slight offsets, applying blur if frosted glass is active
            half4 r_sample = getBlurredColor(distorted_fragCoord_refract + ab_offset_px, frostedGlassBlurRadius * iResolution.y);
            half4 g_sample = getBlurredColor(distorted_fragCoord_refract, frostedGlassBlurRadius * iResolution.y);
            half4 b_sample = getBlurredColor(distorted_fragCoord_refract - ab_offset_px, frostedGlassBlurRadius * iResolution.y);

            refracted_color.rgb = half3(r_sample.r, g_sample.g, b_sample.b);
            refracted_color.a = g_sample.a; // Maintain alpha from green channel
        } else {
            // If no chromatic aberration, just get the (potentially blurred) color
            refracted_color = getBlurredColor(distorted_fragCoord_refract, frostedGlassBlurRadius * iResolution.y);
        }

        // Calculate distorted coordinates for sampling the background content for reflection.
        float2 distorted_fragCoord_reflect = fragCoord + reflected_ray_3d.xy * thickness * iResolution.y / max(0.001, normal_3d.z) * distortion_factor;
        half4 reflected_color = content.eval(distorted_fragCoord_reflect); // Reflection is not blurred or aberrated

        // Simulate a specular highlight to give the illusion of a flat bevel/thickness.
        float shininess = 200.0; // Controls the sharpness of the highlight (higher for sharper bevel)
        // Calculate base specular intensity based on light reflection off the normal
        float specular_base = pow(max(0.0, dot(reflect(-LIGHT_DIR_HIGHLIGHT, normal_3d), -VIEW_DIR)), shininess);

        // Create a mask to concentrate the highlight at the edge (bevel effect).
        // This smoothstep makes the highlight primarily visible in a narrow band at the circle's edge.
        float bevel_mask = smoothstep(radius - bevelWidth, radius, d);
        float specular = specular_base * highlightStrength * bevel_mask;
        half3 highlight_color = half3(1.0, 1.0, 1.0) * specular; // White highlight

        // Combine refracted and reflected colors using the Fresnel term, then add the highlight.
        half3 glass_rgb = mix(refracted_color.rgb, reflected_color.rgb, fresnel_term);
        glass_rgb += highlight_color;

        // Final color inside the circle
        finalColor = half4(glass_rgb, refracted_color.a);

    } else {
        // Outside the circle, apply the background with the cast shadow
        finalColor = backgroundWithShadow;
    }

    // Smoothstep for anti-aliasing the circle's edge, blending the inside glass effect
    // with the outside background+shadow.
    float edgeSoftness = 0.01; // Small value for a sharp visual edge
    finalColor = mix(
        backgroundWithShadow, // Color outside the circle
        finalColor,           // Color inside the circle
        smoothstep(edgeSoftness, -edgeSoftness, d - radius) // 'd - radius' serves as the SDF value
    );

    return finalColor;
}
"""

@Composable
fun SimpleShader() {
    // Initialize the RuntimeShader with the AGSL source code.
    val shader = remember { RuntimeShader(SHADER_SRC) }
    // State to hold the size of the Box containing the content.
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    // State to store the current position of the draggable circle (in pixels).
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    // State to store the Index of Refraction (IOR) value, defaulting to 1.33 for water/glass.
    var iorValue by remember { mutableStateOf(1.33f) }
    // State to store the highlight strength for specular highlights, defaulting to 1.0.
    var highlightStrengthValue by remember { mutableStateOf(1.0f) }
    // State to store the normalized width of the bevel highlight band.
    var bevelWidthValue by remember { mutableStateOf(0.02f) }
    // State to store the perceived thickness of the glass for distortion scaling.
    var thicknessValue by remember { mutableStateOf(0.05f) }
    // State to store the intensity of the cast shadow.
    var shadowIntensityValue by remember { mutableStateOf(0.1f) }
    // State to store the normalized radius of the glass circle.
    var circleRadius by remember { mutableStateOf(0.15f) }
    // State to store the chromatic aberration strength.
    var chromaticAberrationStrength by remember { mutableStateOf(0.001f) }
    // State to store the frosted glass blur radius.
    var frostedGlassBlurRadius by remember { mutableStateOf(0.000f) }
    // Scrollstate
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize() // Make the column fill the entire screen.
            .background(MaterialTheme.colorScheme.background) // Set a blue background for the column.
            .padding(16.dp) // Add padding around the column content.
            .onSizeChanged {
                // Update boxSize when the size of the column changes.
                boxSize = it
                // Initialize dragOffset to the center of the content box on load.
                dragOffset = Offset(it.width / 2f, it.height / 2f)
            }
            .pointerInput(Unit) {
                // Enable drag gestures on the column.
                detectDragGestures { change, dragAmount ->
                    change.consume() // Consume the drag event.
                    // Update dragOffset, ensuring it stays within the bounds of the box.
                    dragOffset = (dragOffset + dragAmount).let { new ->
                        Offset(
                            x = new.x.coerceIn(0f, boxSize.width.toFloat()),
                            y = new.y.coerceIn(0f, boxSize.height.toFloat())
                        )
                    }
                }
            }
            .graphicsLayer {
                // Apply the shader as a render effect to the entire graphics layer.
                if (boxSize.width > 0 && boxSize.height > 0) {
                    // Normalize the center coordinates for the shader (0..1 range).
                    val normalizedCenterX = dragOffset.x / boxSize.width
                    val normalizedCenterY = dragOffset.y / boxSize.height

                    // Set uniforms for the AGSL shader.
                    shader.setFloatUniform("iResolution", boxSize.width.toFloat(), boxSize.height.toFloat())
                    shader.setFloatUniform("radius", circleRadius) // Set the normalized radius of the glass circle using the state variable.
                    shader.setFloatUniform("center", normalizedCenterX, normalizedCenterY)
                    shader.setFloatUniform("ior", iorValue) // Pass the current IOR value to the shader.
                    shader.setFloatUniform("highlightStrength", highlightStrengthValue) // Pass the highlight strength.
                    shader.setFloatUniform("bevelWidth", bevelWidthValue) // Pass the bevel width.
                    shader.setFloatUniform("thickness", thicknessValue) // Pass the thickness.
                    shader.setFloatUniform("shadowIntensity", shadowIntensityValue) // Pass the shadow intensity.
                    shader.setFloatUniform("chromaticAberrationStrength", chromaticAberrationStrength) // Pass the chromatic aberration strength.
                    shader.setFloatUniform("frostedGlassBlurRadius", frostedGlassBlurRadius) // Pass the frosted glass blur radius.


                    // Create and apply the RuntimeShaderEffect.
                    renderEffect = RenderEffect
                        .createRuntimeShaderEffect(shader, "content")
                        .asComposeRenderEffect()
                }
            }
    ) {
        Text("Glass Shader", fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 56.sp, modifier = Modifier.fillMaxWidth().height(56.dp))
        // Box containing the content that will be refracted.
        Box(
            modifier = Modifier
                .width(400.dp) // Fixed width for the content box.
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
            , // Fixed height for the content box.
            contentAlignment = Alignment.Center // Center the content within this box.
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize() // Make the inner box fill the parent box
                    .background(MaterialTheme.colorScheme.secondaryContainer), // Set a gray background for the content.

            ) {
               Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(scrollState)){
                   Text("Glass \nProperties".uppercase(), fontSize = 44.sp, lineHeight = 50.sp, color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Bold,)
                   Text("Glass is an amorphous solid, meaning it lacks a long-range ordered atomic structure, unlike crystalline materials. This unique atomic arrangement gives rise to its characteristic properties. Optically, glass is often transparent due to the absence of grain boundaries that scatter light, and its refractive index can be engineered for various applications. Mechanically, it is brittle, exhibiting elastic deformation up to a certain point before sudden fracture without significant plastic deformation. Its strength is highly dependent on surface imperfections. Thermally, glass is a poor conductor of heat and undergoes a continuous softening as temperature increases, rather than a sharp melting point, transitioning through a \"glass transition temperature\" where it transforms from a rigid to a more viscous state. Chemically, most common glasses are highly resistant to corrosion from water and many chemicals, though certain strong acids or bases can etch them over time.", fontSize = 14.sp,  lineHeight = 20.sp ,color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(top = 8.dp).fillMaxWidth())
               }
            }
        }
        Spacer(Modifier.weight(0.1f)) // Spacer to push content towards center.

        // Slider to control the Circle Radius.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Radius:", fontSize = 12.sp, fontWeight = FontWeight.Bold,  modifier = Modifier.width(70.dp))
            Slider(
                value = circleRadius,
                onValueChange = { circleRadius = it },
                valueRange = 0.05f..0.5f, // Normalized radius range
                steps = 100, // Number of discrete steps for the slider.
                modifier = Modifier.weight(1f) // Make the slider fill available width.
            )
            Spacer(Modifier.width(8.dp)) // Spacer between slider and text.
            Text(String.format("%.2f", circleRadius), fontSize = 12.sp,  modifier = Modifier.width(40.dp)) // Display current radius value.
        }

        // Slider to control the Index of Refraction (IOR).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("IOR:", fontSize = 12.sp, fontWeight = FontWeight.Bold,  modifier = Modifier.width(70.dp))
            Slider(
                value = iorValue,
                onValueChange = { iorValue = it },
                valueRange = 1.0f..2.0f, // IOR typically ranges from 1.0 (air) to 2.0+
                steps = 100, // Number of discrete steps for the slider.
                modifier = Modifier.weight(1f) // Make the slider fill available width.
            )
            Spacer(Modifier.width(8.dp)) // Spacer between slider and text.
            Text(String.format("%.2f", iorValue), fontSize = 12.sp,  modifier = Modifier.width(40.dp)) // Display current IOR value.
        }

        // Slider to control the Highlight Strength for reflections.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Highlight:", fontSize = 12.sp, fontWeight = FontWeight.Bold,  modifier = Modifier.width(70.dp))
            Slider(
                value = highlightStrengthValue,
                onValueChange = { highlightStrengthValue = it },
                valueRange = 0.0f..2.0f, // Adjust range as needed
                steps = 100,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(String.format("%.2f", highlightStrengthValue), fontSize = 12.sp,  modifier = Modifier.width(40.dp))
        }

        // Slider to control the Bevel Width.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Bevel:", fontSize = 12.sp, fontWeight = FontWeight.Bold,  modifier = Modifier.width(70.dp))
            Slider(
                value = bevelWidthValue,
                onValueChange = { bevelWidthValue = it },
                valueRange = 0.0f..0.1f, // Normalized width (0.0 to 0.1 of radius)
                steps = 100,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(String.format("%.3f", bevelWidthValue), fontSize = 12.sp,  modifier = Modifier.width(40.dp))
        }

        // Slider to control the Thickness.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Thickness:", fontSize = 12.sp, fontWeight = FontWeight.Bold,  modifier = Modifier.width(70.dp))
            Slider(
                value = thicknessValue,
                onValueChange = { thicknessValue = it },
                valueRange = 0.0f..0.1f, // Adjust range as needed
                steps = 100,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(String.format("%.3f", thicknessValue), fontSize = 12.sp,  modifier = Modifier.width(40.dp))
        }

        // Slider to control the Shadow Intensity.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Shadow:", fontSize = 12.sp, fontWeight = FontWeight.Bold,  modifier = Modifier.width(70.dp))
            Slider(
                value = shadowIntensityValue,
                onValueChange = { shadowIntensityValue = it },
                valueRange = 0.0f..1.0f, // Adjust range as needed
                steps = 100,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(String.format("%.2f", shadowIntensityValue), fontSize = 12.sp,  modifier = Modifier.width(40.dp))
        }

        // Slider to control Chromatic Aberration Strength.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Chromatic:", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(70.dp))
            Slider(
                value = chromaticAberrationStrength,
                onValueChange = { chromaticAberrationStrength = it },
                valueRange = 0.0f..0.005f, // Small range for subtle effect
                steps = 100,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(String.format("%.4f", chromaticAberrationStrength), fontSize = 12.sp, modifier = Modifier.width(40.dp))
        }

        // Slider to control Frosted Glass Blur Radius.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Frosted:", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(70.dp))
            Slider(
                value = frostedGlassBlurRadius,
                onValueChange = { frostedGlassBlurRadius = it },
                valueRange = 0.0f..0.02f, // Small normalized radius for blur
                steps = 100,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(String.format("%.3f", frostedGlassBlurRadius), fontSize = 12.sp, modifier = Modifier.width(40.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    ShaderLabsTheme {
        SimpleShader()
    }
}
