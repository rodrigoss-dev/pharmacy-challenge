Feature: Check-in
  A patient arrives with a prescription from their doctor and checks in to get a ticket number.

  Scenario: The prescription is received
    Given a patient named "  Maria Silva "
    When they check in with 2 packs of Amoxicillin 500 mg
    Then their prescription is RECEIVED
    And it is in the name of "Maria Silva"
    And it was last updated at the time of check-in

  Rule: The patient gives a name of up to 60 characters

    Scenario: No name
      Given a patient named " "
      When they check in with 2 packs of Amoxicillin 500 mg
      Then they are told "Please tell us your name"

    Scenario: A name that is too long
      Given a patient whose name is 61 characters long
      When they check in with 2 packs of Amoxicillin 500 mg
      Then they are told "Name is too long"

    Scenario: A name of exactly 60 characters
      Given a patient whose name is 60 characters long
      When they check in with 2 packs of Amoxicillin 500 mg
      Then their prescription is RECEIVED

  Rule: A prescription lists each medication once, with at least 1 pack of it

    Background:
      Given a patient named "Maria"

    Scenario: No medication
      When they check in with no medication
      Then they are told "A prescription needs at least one medication"

    Scenario: The same medication listed twice
      When they check in with:
        | medication         | packs |
        | Amoxicillin 500 mg | 2     |
        | Amoxicillin 500 mg | 1     |
      Then they are told "Each medication can only be listed once"

    Scenario Outline: <packs> packs of a medication
      When they check in with <packs> packs of Amoxicillin 500 mg
      Then they are told "Quantity of Amoxicillin 500 mg must be at least 1"

      Examples:
        | packs |
        | 0     |
        | -1    |

    Scenario Outline: <packs> packs of a medication are fine
      When they check in with <packs> packs of Amoxicillin 500 mg
      Then their prescription is RECEIVED

      Examples:
        | packs |
        | 1     |
        | 10    |
        | 11    |
        | 500   |
