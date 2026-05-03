package com.forensicppg.monitor.ppg

/**
 * Bibliografía reproducible enlazada a decisiones metodológicas de la capa contacto-cPPG
 * **sin fingir valores clínicos** ni “calibraciones” ficticias.
 *
 * Cuando cambies umbrales de detección, documentá la fuente experimental (ECG, laboratorio),
 * igual que establecen estas revisiones para reporte válido FC vía smartphone.
 */
object ContactPpgLiteratureAnchors {
    /**
     * Mather JD, Hayes LD, Mair JL, Sculthorpe NF. *Validity of resting heart rate derived from
     * contact-based smartphone photoplethysmography compared with electrocardiography:
     * a scoping review and checklist for optimal acquisition and reporting.*
     * Front. Digit. Health 2024. — Checklist ítem a ítem: sitio dedo, postura, antorcha, FPS,
     * período estabilización, duración grabación vs ECG. Concluye acuerdos **bajo laboratorio**.
     *
     * open access: doi:[10.3389/fdgth.2024.1326511](https://doi.org/10.3389/fdgth.2024.1326511)
     */
    const val MATHER_SCOPING_FRONT_DIG_HEALTH_2024_DOI = "10.3389/fdgth.2024.1326511"
    const val MATHER_SCOPING_FRONT_DIG_HEALTH_2024_URL =
        "https://www.frontiersin.org/journals/digital-health/articles/10.3389/fdgth.2024.1326511/full"

    /**
     * Xuan Y, Barry C, Antipa N, Wang EJ. *A calibration method for smartphone camera photoplethysmography.*
     * Front. Digit. Health 2023 — **Tone mapping lineal**, **Zero Light Offset (ZLO)** por modelo sensor,
     * banco óptico; sin eso RoR oximetría se sesga (**no substituible por constantes mágicas**).
     *
     * doi:[10.3389/fdgth.2023.1301019](https://doi.org/10.3389/fdgth.2023.1301019)
     */
    const val XUAN_WANG_CALIBRATION_FRONT_DIG_HEALTH_2023_DOI = "10.3389/fdgth.2023.1301019"

    /** Metadatos reproducibles esperados cuando se reclame equivalencia FC-PPG / FC-ECG (corr. ρ, IC95). */
    const val MEDRXIV_META_PROTOCOL_CONTACT_SMARTPHONE_vs_ECG_2025_DOI =
        "10.1101/2025.06.13.25329618"
}
