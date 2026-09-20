package com.iplacex.qa.reservas.unit;

import com.iplacex.qa.reservas.servicio.CalculadoraTarifas;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NIVEL 1 - PRUEBAS UNITARIAS de la lógica de tarifas.
 *
 * <p>Características que hacen de esta suite un buen ciudadano del pipeline:</p>
 * <ul>
 *   <li><b>Idempotencia:</b> no tocan disco, red ni reloj del sistema; pueden
 *       repetirse indefinidamente con idéntico resultado.</li>
 *   <li><b>Velocidad:</b> se ejecutan en milisegundos, apropiadas para la etapa
 *       de commit donde el feedback debe ser inmediato.</li>
 *   <li><b>Parametrización:</b> {@code @ParameterizedTest} cubre múltiples casos
 *       de borde sin duplicar código de prueba.</li>
 * </ul>
 */
@DisplayName("Calculadora de tarifas - reglas de negocio")
class CalculadoraTarifasTest {

    private CalculadoraTarifas calculadora;

    /** Fixture: instancia limpia antes de cada prueba, garantizando aislamiento. */
    @BeforeEach
    void prepararFixture() {
        calculadora = new CalculadoraTarifas();
    }

    @Nested
    @DisplayName("Cálculo del total")
    class CalculoTotal {

        @Test
        @DisplayName("Aplica la tarifa base en temporada normal sin descuentos")
        void calculaTarifaBaseEnTemporadaNormal() {
            // Junio: temporada normal. 3 noches x 2 personas x 45.000 = 270.000
            double total = calculadora.calcularTotal(3, 2, LocalDate.of(2026, 6, 10));

            assertThat(total).isEqualTo(270_000.0);
        }

        @Test
        @DisplayName("Aplica un recargo del 25% en temporada alta")
        void aplicaRecargoTemporadaAlta() {
            // Enero: temporada alta. 270.000 + 25% = 337.500
            double total = calculadora.calcularTotal(3, 2, LocalDate.of(2026, 1, 10));

            assertThat(total).isEqualTo(337_500.0);
        }

        @Test
        @DisplayName("Aplica un descuento del 10% desde la séptima noche")
        void aplicaDescuentoEstadiaLarga() {
            // Junio, 7 noches x 1 persona = 315.000 - 10% = 283.500
            double total = calculadora.calcularTotal(7, 1, LocalDate.of(2026, 6, 10));

            assertThat(total).isEqualTo(283_500.0);
        }

        @Test
        @DisplayName("Combina recargo de temporada alta y descuento por estadía larga")
        void combinaRecargoYDescuento() {
            // Enero, 7 noches x 2 personas: 630.000 + 25% = 787.500 - 10% = 708.750
            double total = calculadora.calcularTotal(7, 2, LocalDate.of(2026, 1, 15));

            assertThat(total).isEqualTo(708_750.0);
        }

        @ParameterizedTest(name = "{0} noches x {1} personas en {2} => {3}")
        @DisplayName("Verifica combinaciones representativas de tarifas")
        @CsvSource({
                "1, 1, 2026-06-01, 45000.0",
                "2, 3, 2026-07-20, 270000.0",
                "5, 2, 2026-12-28, 562500.0",
                "10, 1, 2026-03-05, 405000.0",
                "7, 8, 2026-02-14, 2835000.0"
        })
        void verificaCombinacionesDeTarifas(int noches, int personas, String fecha, double esperado) {
            double total = calculadora.calcularTotal(noches, personas, LocalDate.parse(fecha));

            assertThat(total).isEqualTo(esperado);
        }
    }

    @Nested
    @DisplayName("Validación de parámetros")
    class ValidacionParametros {

        @ParameterizedTest(name = "noches = {0} debe ser rechazado")
        @ValueSource(ints = {0, -1, -15})
        void rechazaNochesNoPositivas(int noches) {
            assertThatThrownBy(() -> calculadora.calcularTotal(noches, 2, LocalDate.of(2026, 6, 1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("noches");
        }

        @Test
        @DisplayName("Rechaza reservas sin personas")
        void rechazaCeroPersonas() {
            assertThatThrownBy(() -> calculadora.calcularTotal(3, 0, LocalDate.of(2026, 6, 1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("al menos una persona");
        }

        @Test
        @DisplayName("Rechaza grupos que exceden el máximo permitido")
        void rechazaExcesoDePersonas() {
            assertThatThrownBy(() -> calculadora.calcularTotal(3, 9, LocalDate.of(2026, 6, 1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("más de 8 personas");
        }

        @Test
        @DisplayName("Rechaza fecha de inicio nula")
        void rechazaFechaNula() {
            assertThatThrownBy(() -> calculadora.calcularTotal(3, 2, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("fecha de inicio");
        }
    }

    @Nested
    @DisplayName("Clasificación de temporada")
    class Temporada {

        @ParameterizedTest(name = "{0} es temporada alta")
        @ValueSource(strings = {"2026-12-15", "2026-01-05", "2026-02-28"})
        void identificaTemporadaAlta(String fecha) {
            assertThat(calculadora.esTemporadaAlta(LocalDate.parse(fecha))).isTrue();
        }

        @ParameterizedTest(name = "{0} es temporada normal")
        @ValueSource(strings = {"2026-03-01", "2026-06-30", "2026-09-18", "2026-11-30"})
        void identificaTemporadaNormal(String fecha) {
            assertThat(calculadora.esTemporadaAlta(LocalDate.parse(fecha))).isFalse();
        }

        @Test
        @DisplayName("El descuento por estadía larga comienza exactamente en 7 noches")
        void verificaLimiteDescuento() {
            assertThat(calculadora.aplicaDescuentoEstadiaLarga(6)).isFalse();
            assertThat(calculadora.aplicaDescuentoEstadiaLarga(7)).isTrue();
        }
    }
}
