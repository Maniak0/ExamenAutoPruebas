# language: es
# ============================================================================
#  Especificación ejecutable (BDD) del Sistema de Reservas Turísticas
# ----------------------------------------------------------------------------
#  Este archivo cumple una doble función, tal como plantea el Módulo de
#  Estudio 4: es DOCUMENTACIÓN legible por el área de negocio y, al mismo
#  tiempo, un CASO DE PRUEBA EJECUTABLE. Al versionarse junto al código, la
#  especificación nunca envejece respecto del sistema que describe.
#
#  Está redactado en lenguaje de negocio: describe el QUÉ espera el cliente,
#  nunca el CÓMO técnico, que vive en las step definitions.
# ============================================================================

Característica: Gestión de reservas turísticas
  Como operador turístico
  Quiero administrar las reservas de mis clientes
  Para asegurar la disponibilidad y cobrar la tarifa correcta

  Antecedentes:
    Dado que el servicio de reservas está disponible

  @aceptacion @smoke
  Escenario: Registrar una reserva en temporada normal
    Cuando el cliente "Patricia Núñez" reserva "Valle del Elqui" para el "2026-06-10" por 3 noches y 2 personas
    Entonces la reserva queda confirmada
    Y el valor total de la reserva es 270000

  @aceptacion
  Escenario: Registrar una reserva en temporada alta con estadía prolongada
    Cuando el cliente "Rodrigo Silva" reserva "Isla de Pascua" para el "2026-01-20" por 7 noches y 2 personas
    Entonces la reserva queda confirmada
    Y el valor total de la reserva es 708750

  @aceptacion
  Escenario: Cancelar una reserva vigente
    Dado que el cliente "Marcela Fuentes" tiene una reserva para el "2026-07-05"
    Cuando solicita la cancelación de su reserva
    Entonces la reserva queda cancelada

  @aceptacion
  Escenario: Impedir reservas duplicadas
    Dado que el cliente "Andrés Muñoz" tiene una reserva para el "2026-08-12"
    Cuando el cliente "Andrés Muñoz" reserva "Pucón" para el "2026-08-12" por 2 noches y 2 personas
    Entonces el sistema rechaza la solicitud por conflicto

  @aceptacion
  Esquema del escenario: Validar los límites de capacidad de una reserva
    Cuando el cliente "Cliente Limite" reserva "Chiloé" para el "<fecha>" por <noches> noches y <personas> personas
    Entonces el sistema responde con el código <codigo>

    Ejemplos:
      | fecha      | noches | personas | codigo |
      | 2026-09-01 | 3      | 8        | 201    |
      | 2026-09-02 | 3      | 9        | 400    |
      | 2026-09-03 | 0      | 2        | 400    |
