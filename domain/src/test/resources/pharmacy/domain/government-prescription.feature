Feature: Government prescriptions
  A patient can check in with the number of a government prescription instead of typing their medication. It lists what
  they may collect, and how much. Some quantities are a maximum; the others are taken as prescribed. Once the patient
  has their medication, what they took is committed to the government system.

  Background:
    Given the government prescription GOV-1001 lists:
      | medication         | packs | maximum |
      | Amoxicillin 500 mg | 1     | no      |
      | Ibuprofen 400 mg   | 2     | yes     |

  Rule: A medication with a maximum can be taken in any quantity up to it

    Scenario Outline: <packs> pack(s) of a medication with a maximum of 2
      When the patient checks in on it with <packs> packs of Ibuprofen 400 mg
      Then the check-in is <result>

      Examples:
        | packs | result                                                                               |
        | 1     | accepted                                                                             |
        | 2     | accepted                                                                             |
        | 3     | refused with "Government prescription GOV-1001 allows at most 2 packs of Ibuprofen 400 mg" |
        | 0     | refused with "Quantity of Ibuprofen 400 mg must be at least 1"                       |

  Rule: A medication without a maximum is taken as prescribed

    Scenario: The prescribed quantity
      When the patient checks in on it with 1 packs of Amoxicillin 500 mg
      Then the check-in is accepted

    Scenario: More than prescribed
      When the patient checks in on it with 2 packs of Amoxicillin 500 mg
      Then the check-in is refused with "Government prescription GOV-1001 covers 1 pack of Amoxicillin 500 mg, no more and no less"

  Rule: Only medication on the government prescription can be added

    Scenario: A medication that is not on it
      When the patient checks in on it with 1 packs of Metformin 850 mg
      Then the check-in is refused with "Metformin 850 mg is not on government prescription GOV-1001"

    Scenario: Some of the medication left out
      When the patient checks in on it with 1 packs of Ibuprofen 400 mg
      Then the check-in is accepted

    Scenario: A medication that was used up already is not "not on it"
      Given all of the Amoxicillin 500 mg on it was collected already
      When the patient checks in on it with 1 packs of Amoxicillin 500 mg
      Then the check-in is refused with "Government prescription GOV-1001 has no Amoxicillin 500 mg left to collect"

  Rule: Medication that another ticket on the same prescription holds is not available twice
    Two phones can check in on the same number before either patient has collected anything: the government system
    is told what was taken only after the hand-over, so the pharmacy counts what its own tickets hold.

    Scenario: The whole maximum is held by an earlier ticket
      Given 2 packs of Ibuprofen 400 mg are already promised on it
      When the patient checks in on it with 1 packs of Ibuprofen 400 mg
      Then the check-in is refused with "Government prescription GOV-1001 has no Ibuprofen 400 mg left to collect (2 packs of it already on a ticket that is still in progress)"

    Scenario: Part of the maximum is held
      Given 1 packs of Ibuprofen 400 mg are already promised on it
      When the patient checks in on it with 2 packs of Ibuprofen 400 mg
      Then the check-in is refused with "Government prescription GOV-1001 allows at most 1 pack of Ibuprofen 400 mg (1 pack of it already on a ticket that is still in progress)"

    Scenario: What is left of the maximum can still be taken
      Given 1 packs of Ibuprofen 400 mg are already promised on it
      When the patient checks in on it with 1 packs of Ibuprofen 400 mg
      Then the check-in is accepted

    Scenario: Medication taken as prescribed is held whole
      Given 1 packs of Amoxicillin 500 mg are already promised on it
      When the patient checks in on it with 1 packs of Amoxicillin 500 mg
      Then the check-in is refused with "Government prescription GOV-1001 has no Amoxicillin 500 mg left to collect (1 pack of it already on a ticket that is still in progress)"

    Scenario: Other medication on it is not affected
      Given 2 packs of Ibuprofen 400 mg are already promised on it
      When the patient checks in on it with 1 packs of Amoxicillin 500 mg
      Then the check-in is accepted

  Rule: What the patient took is what gets committed, once they have it

    Scenario: A maximum of 2, and the patient takes 1
      Given the patient checked in on it with 1 packs of Ibuprofen 400 mg
      When the patient collects it
      Then this is committed to GOV-1001:
        | medication       | packs |
        | Ibuprofen 400 mg | 1     |

    Scenario: Several medications
      Given the patient checked in on it with:
        | medication         | packs |
        | Amoxicillin 500 mg | 1     |
        | Ibuprofen 400 mg   | 2     |
      When the patient collects it
      Then this is committed to GOV-1001:
        | medication         | packs |
        | Amoxicillin 500 mg | 1     |
        | Ibuprofen 400 mg   | 2     |

    Scenario: Nothing is committed before the patient has their medication
      Given the patient checked in on it with 1 packs of Ibuprofen 400 mg
      Then nothing is committed

    Scenario: A prescription typed in by hand has nothing to commit
      Given the patient checked in without a government prescription, with 1 packs of Ibuprofen 400 mg
      When the patient collects it
      Then nothing is committed
