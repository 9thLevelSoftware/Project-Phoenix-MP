package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.compottie.Compottie
import io.github.alexzhirkevich.compottie.LottieCompositionSpec
import io.github.alexzhirkevich.compottie.rememberLottieComposition
import io.github.alexzhirkevich.compottie.rememberLottiePainter

/**
 * Reusable Lottie animation component for Compose Multiplatform.
 * Uses Compottie library for cross-platform Lottie rendering.
 *
 * @param animationJson The raw JSON string of the Lottie animation
 * @param modifier Modifier for the animation
 * @param size Size of the animation container
 * @param iterations Number of times to play (use Compottie.IterateForever for infinite loop)
 * @param speed Playback speed multiplier (1.0 = normal)
 * @param contentDescription Accessibility description
 */
@Composable
fun LottieAnimation(
    animationJson: String,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    iterations: Int = Compottie.IterateForever,
    speed: Float = 1f,
    contentDescription: String? = null
) {
    val composition by rememberLottieComposition {
        LottieCompositionSpec.JsonString(animationJson)
    }

    Image(
        painter = rememberLottiePainter(
            composition = composition,
            iterations = iterations,
            speed = speed
        ),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size)
    )
}

/**
 * Predefined celebration animations.
 * These are simple placeholder animations - replace with proper Lottie files from LottieFiles.com
 * for production quality animations.
 *
 * Recommended free animations from LottieFiles:
 * - Confetti: https://lottiefiles.com/animations/confetti-celebration-zyLM6yb8Sx
 * - Trophy: https://lottiefiles.com/animations/trophy-pCvNRNf8uF
 * - Checkmark: https://lottiefiles.com/animations/success-check-mark-7rvzVg6Y5l
 * - Fireworks: https://lottiefiles.com/animations/fireworks-celebration-TdGzWvgBEr
 */
object CelebrationAnimations {

    /**
     * Simple confetti burst animation placeholder.
     * Replace with a proper Lottie JSON from LottieFiles for production.
     */
    val confetti = """
    {
      "v": "5.7.4",
      "fr": 60,
      "ip": 0,
      "op": 120,
      "w": 400,
      "h": 400,
      "nm": "Confetti",
      "ddd": 0,
      "assets": [],
      "layers": [
        {
          "ddd": 0,
          "ind": 1,
          "ty": 4,
          "nm": "Particle 1",
          "sr": 1,
          "ks": {
            "o": { "a": 1, "k": [{"t": 0, "s": [100]}, {"t": 120, "s": [0]}] },
            "p": { "a": 1, "k": [{"t": 0, "s": [200, 200, 0]}, {"t": 120, "s": [100, 400, 0]}] },
            "s": { "a": 0, "k": [100, 100, 100] },
            "r": { "a": 1, "k": [{"t": 0, "s": [0]}, {"t": 120, "s": [720]}] }
          },
          "shapes": [{
            "ty": "rc",
            "d": 1,
            "s": { "a": 0, "k": [20, 20] },
            "p": { "a": 0, "k": [0, 0] },
            "r": { "a": 0, "k": 0 }
          }, {
            "ty": "fl",
            "c": { "a": 0, "k": [1, 0.84, 0, 1] },
            "o": { "a": 0, "k": 100 }
          }]
        },
        {
          "ddd": 0,
          "ind": 2,
          "ty": 4,
          "nm": "Particle 2",
          "sr": 1,
          "ks": {
            "o": { "a": 1, "k": [{"t": 0, "s": [100]}, {"t": 120, "s": [0]}] },
            "p": { "a": 1, "k": [{"t": 0, "s": [200, 200, 0]}, {"t": 120, "s": [300, 380, 0]}] },
            "s": { "a": 0, "k": [100, 100, 100] },
            "r": { "a": 1, "k": [{"t": 0, "s": [0]}, {"t": 120, "s": [-540]}] }
          },
          "shapes": [{
            "ty": "rc",
            "d": 1,
            "s": { "a": 0, "k": [16, 16] },
            "p": { "a": 0, "k": [0, 0] },
            "r": { "a": 0, "k": 0 }
          }, {
            "ty": "fl",
            "c": { "a": 0, "k": [0.58, 0.2, 0.92, 1] },
            "o": { "a": 0, "k": 100 }
          }]
        },
        {
          "ddd": 0,
          "ind": 3,
          "ty": 4,
          "nm": "Particle 3",
          "sr": 1,
          "ks": {
            "o": { "a": 1, "k": [{"t": 0, "s": [100]}, {"t": 120, "s": [0]}] },
            "p": { "a": 1, "k": [{"t": 0, "s": [200, 200, 0]}, {"t": 120, "s": [150, 420, 0]}] },
            "s": { "a": 0, "k": [100, 100, 100] },
            "r": { "a": 1, "k": [{"t": 0, "s": [0]}, {"t": 120, "s": [480]}] }
          },
          "shapes": [{
            "ty": "el",
            "d": 1,
            "s": { "a": 0, "k": [14, 14] },
            "p": { "a": 0, "k": [0, 0] }
          }, {
            "ty": "fl",
            "c": { "a": 0, "k": [1, 0.41, 0.71, 1] },
            "o": { "a": 0, "k": 100 }
          }]
        },
        {
          "ddd": 0,
          "ind": 4,
          "ty": 4,
          "nm": "Particle 4",
          "sr": 1,
          "ks": {
            "o": { "a": 1, "k": [{"t": 0, "s": [100]}, {"t": 120, "s": [0]}] },
            "p": { "a": 1, "k": [{"t": 0, "s": [200, 200, 0]}, {"t": 120, "s": [280, 390, 0]}] },
            "s": { "a": 0, "k": [100, 100, 100] },
            "r": { "a": 1, "k": [{"t": 0, "s": [0]}, {"t": 120, "s": [-360]}] }
          },
          "shapes": [{
            "ty": "rc",
            "d": 1,
            "s": { "a": 0, "k": [12, 18] },
            "p": { "a": 0, "k": [0, 0] },
            "r": { "a": 0, "k": 0 }
          }, {
            "ty": "fl",
            "c": { "a": 0, "k": [0.23, 0.51, 0.96, 1] },
            "o": { "a": 0, "k": 100 }
          }]
        },
        {
          "ddd": 0,
          "ind": 5,
          "ty": 4,
          "nm": "Particle 5",
          "sr": 1,
          "ks": {
            "o": { "a": 1, "k": [{"t": 0, "s": [100]}, {"t": 120, "s": [0]}] },
            "p": { "a": 1, "k": [{"t": 0, "s": [200, 200, 0]}, {"t": 120, "s": [80, 350, 0]}] },
            "s": { "a": 0, "k": [100, 100, 100] },
            "r": { "a": 1, "k": [{"t": 0, "s": [0]}, {"t": 120, "s": [600]}] }
          },
          "shapes": [{
            "ty": "el",
            "d": 1,
            "s": { "a": 0, "k": [18, 18] },
            "p": { "a": 0, "k": [0, 0] }
          }, {
            "ty": "fl",
            "c": { "a": 0, "k": [0.06, 0.73, 0.51, 1] },
            "o": { "a": 0, "k": 100 }
          }]
        }
      ]
    }
    """.trimIndent()

    /**
     * Simple trophy/star celebration animation placeholder.
     * Replace with a proper Lottie JSON from LottieFiles for production.
     */
    val trophy = """
    {
      "v": "5.7.4",
      "fr": 60,
      "ip": 0,
      "op": 90,
      "w": 400,
      "h": 400,
      "nm": "Trophy",
      "ddd": 0,
      "assets": [],
      "layers": [
        {
          "ddd": 0,
          "ind": 1,
          "ty": 4,
          "nm": "Star",
          "sr": 1,
          "ks": {
            "o": { "a": 0, "k": 100 },
            "p": { "a": 0, "k": [200, 200, 0] },
            "s": { "a": 1, "k": [
              {"t": 0, "s": [0, 0, 100]},
              {"t": 20, "s": [120, 120, 100]},
              {"t": 30, "s": [100, 100, 100]},
              {"t": 60, "s": [100, 100, 100]},
              {"t": 90, "s": [110, 110, 100]}
            ]},
            "r": { "a": 1, "k": [{"t": 0, "s": [0]}, {"t": 90, "s": [15]}] }
          },
          "shapes": [{
            "ty": "sr",
            "sy": 1,
            "d": 1,
            "pt": { "a": 0, "k": 5 },
            "p": { "a": 0, "k": [0, 0] },
            "r": { "a": 0, "k": 0 },
            "ir": { "a": 0, "k": 40 },
            "is": { "a": 0, "k": 0 },
            "or": { "a": 0, "k": 100 },
            "os": { "a": 0, "k": 0 }
          }, {
            "ty": "fl",
            "c": { "a": 0, "k": [1, 0.84, 0, 1] },
            "o": { "a": 0, "k": 100 }
          }]
        },
        {
          "ddd": 0,
          "ind": 2,
          "ty": 4,
          "nm": "Glow",
          "sr": 1,
          "ks": {
            "o": { "a": 1, "k": [
              {"t": 0, "s": [0]},
              {"t": 20, "s": [50]},
              {"t": 60, "s": [50]},
              {"t": 90, "s": [30]}
            ]},
            "p": { "a": 0, "k": [200, 200, 0] },
            "s": { "a": 1, "k": [
              {"t": 0, "s": [0, 0, 100]},
              {"t": 30, "s": [150, 150, 100]},
              {"t": 90, "s": [180, 180, 100]}
            ]}
          },
          "shapes": [{
            "ty": "el",
            "d": 1,
            "s": { "a": 0, "k": [200, 200] },
            "p": { "a": 0, "k": [0, 0] }
          }, {
            "ty": "fl",
            "c": { "a": 0, "k": [1, 0.84, 0, 0.3] },
            "o": { "a": 0, "k": 100 }
          }]
        }
      ]
    }
    """.trimIndent()

    /**
     * Simple checkmark success animation placeholder.
     * Replace with a proper Lottie JSON from LottieFiles for production.
     */
    val checkmark = """
    {
      "v": "5.7.4",
      "fr": 60,
      "ip": 0,
      "op": 60,
      "w": 400,
      "h": 400,
      "nm": "Checkmark",
      "ddd": 0,
      "assets": [],
      "layers": [
        {
          "ddd": 0,
          "ind": 1,
          "ty": 4,
          "nm": "Circle",
          "sr": 1,
          "ks": {
            "o": { "a": 0, "k": 100 },
            "p": { "a": 0, "k": [200, 200, 0] },
            "s": { "a": 1, "k": [
              {"t": 0, "s": [0, 0, 100]},
              {"t": 15, "s": [110, 110, 100]},
              {"t": 25, "s": [100, 100, 100]}
            ]}
          },
          "shapes": [{
            "ty": "el",
            "d": 1,
            "s": { "a": 0, "k": [160, 160] },
            "p": { "a": 0, "k": [0, 0] }
          }, {
            "ty": "fl",
            "c": { "a": 0, "k": [0.06, 0.73, 0.51, 1] },
            "o": { "a": 0, "k": 100 }
          }]
        },
        {
          "ddd": 0,
          "ind": 2,
          "ty": 4,
          "nm": "Check",
          "sr": 1,
          "ks": {
            "o": { "a": 1, "k": [{"t": 0, "s": [0]}, {"t": 25, "s": [0]}, {"t": 35, "s": [100]}] },
            "p": { "a": 0, "k": [200, 200, 0] },
            "s": { "a": 1, "k": [
              {"t": 25, "s": [0, 0, 100]},
              {"t": 40, "s": [120, 120, 100]},
              {"t": 50, "s": [100, 100, 100]}
            ]}
          },
          "shapes": [{
            "ty": "sh",
            "d": 1,
            "ks": {
              "a": 0,
              "k": {
                "c": false,
                "v": [[-35, 5], [-10, 30], [40, -25]],
                "i": [[0, 0], [0, 0], [0, 0]],
                "o": [[0, 0], [0, 0], [0, 0]]
              }
            }
          }, {
            "ty": "st",
            "c": { "a": 0, "k": [1, 1, 1, 1] },
            "o": { "a": 0, "k": 100 },
            "w": { "a": 0, "k": 12 },
            "lc": 2,
            "lj": 2
          }]
        }
      ]
    }
    """.trimIndent()

    /**
     * Simple flame/fire animation for streaks.
     * Replace with a proper Lottie JSON from LottieFiles for production.
     */
    val flame = """
    {
      "v": "5.7.4",
      "fr": 60,
      "ip": 0,
      "op": 120,
      "w": 400,
      "h": 400,
      "nm": "Flame",
      "ddd": 0,
      "assets": [],
      "layers": [
        {
          "ddd": 0,
          "ind": 1,
          "ty": 4,
          "nm": "Flame Core",
          "sr": 1,
          "ks": {
            "o": { "a": 0, "k": 100 },
            "p": { "a": 0, "k": [200, 220, 0] },
            "s": { "a": 1, "k": [
              {"t": 0, "s": [100, 100, 100]},
              {"t": 30, "s": [95, 110, 100]},
              {"t": 60, "s": [105, 95, 100]},
              {"t": 90, "s": [98, 105, 100]},
              {"t": 120, "s": [100, 100, 100]}
            ]}
          },
          "shapes": [{
            "ty": "sh",
            "d": 1,
            "ks": {
              "a": 0,
              "k": {
                "c": true,
                "v": [[0, -80], [50, 20], [30, 60], [0, 40], [-30, 60], [-50, 20]],
                "i": [[0, 0], [20, -30], [10, 10], [10, 10], [-10, 10], [-20, -30]],
                "o": [[20, 30], [10, 20], [-10, 10], [-10, 10], [-10, 20], [-20, 30]]
              }
            }
          }, {
            "ty": "fl",
            "c": { "a": 0, "k": [1, 0.6, 0, 1] },
            "o": { "a": 0, "k": 100 }
          }]
        },
        {
          "ddd": 0,
          "ind": 2,
          "ty": 4,
          "nm": "Flame Inner",
          "sr": 1,
          "ks": {
            "o": { "a": 0, "k": 100 },
            "p": { "a": 0, "k": [200, 230, 0] },
            "s": { "a": 1, "k": [
              {"t": 0, "s": [70, 70, 100]},
              {"t": 20, "s": [65, 80, 100]},
              {"t": 40, "s": [75, 65, 100]},
              {"t": 60, "s": [68, 75, 100]},
              {"t": 80, "s": [72, 68, 100]},
              {"t": 100, "s": [68, 72, 100]},
              {"t": 120, "s": [70, 70, 100]}
            ]}
          },
          "shapes": [{
            "ty": "sh",
            "d": 1,
            "ks": {
              "a": 0,
              "k": {
                "c": true,
                "v": [[0, -60], [35, 10], [20, 40], [0, 25], [-20, 40], [-35, 10]],
                "i": [[0, 0], [15, -20], [8, 8], [8, 8], [-8, 8], [-15, -20]],
                "o": [[15, 20], [8, 15], [-8, 8], [-8, 8], [-8, 15], [-15, 20]]
              }
            }
          }, {
            "ty": "fl",
            "c": { "a": 0, "k": [1, 0.84, 0, 1] },
            "o": { "a": 0, "k": 100 }
          }]
        }
      ]
    }
    """.trimIndent()
}
