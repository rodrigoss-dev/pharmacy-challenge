Feature: Prescription lifecycle
  After check-in, a prescription goes through the stock check, the pharmacist, packaging and the pickup counter:

    RECEIVED -> AWAITING_APPROVAL -> AWAITING_PACKAGING -> READY_FOR_PICKUP -> CALLED -> COLLECTED
        \-> OUT_OF_STOCK      \-> REJECTED                                          \-> NO_SHOW

  Scenario: From prescription to patient
    Given a prescription that is RECEIVED
    When the stock is reserved
    Then it is AWAITING_APPROVAL
    When the pharmacist approves it
    Then it is AWAITING_PACKAGING
    When it is packed
    Then it is READY_FOR_PICKUP
    When the patient's number is called
    Then it is CALLED
    When it is handed over
    Then it is COLLECTED

  Scenario: Calling a number again
    Patients do not always hear their number the first time.

    Given a prescription that is CALLED
    When the patient's number is called again
    Then it is CALLED

  Scenario: The patient does not answer
    Patients do not always come when their number is called. After calling again, the pharmacist gives the number up.

    Given a prescription that is CALLED
    When the patient does not answer
    Then it is NO_SHOW
    And the reason given is "Nobody answered when number 7 was called"

  Scenario: Out of stock, saying which medication is short
    Given a prescription for:
      | medication         | packs |
      | Amoxicillin 500 mg | 2     |
      | Ibuprofen 400 mg   | 1     |
    When there is not enough stock of Ibuprofen 400 mg
    Then it is OUT_OF_STOCK
    And the reason given is "Not enough stock of Ibuprofen 400 mg"

  Scenario: The pharmacist rejects a prescription
    Given a prescription that is AWAITING_APPROVAL
    When the pharmacist rejects it with the reason "Allergy on file"
    Then it is REJECTED
    And the reason given is "Allergy on file"

  Scenario: A reason is kept without the spaces around it
    Given a prescription that is AWAITING_APPROVAL
    When the pharmacist rejects it with the reason "  Allergy on file  "
    Then the reason given is "Allergy on file"

  Scenario: A rejection needs a reason
    Given a prescription that is AWAITING_APPROVAL
    When the pharmacist rejects it with the reason "  "
    Then the pharmacist is told "A reason is required to reject a prescription"

  Scenario Outline: The reason is read on a phone, so it is short
    Given a prescription that is AWAITING_APPROVAL
    When the pharmacist rejects it with a reason of <length> characters
    Then <result>

    Examples:
      | length | result                                                                                |
      | 200    | it is REJECTED                                                                        |
      | 201    | the pharmacist is told "The reason is too long: please use at most 200 characters"   |

  Rule: Steps cannot be skipped or repeated

    Scenario: Staff are told where the prescription is, in words
      Given a prescription that is AWAITING_PACKAGING
      When the pharmacist approves it
      Then the staff member is told "Prescription #7 is already waiting to be packed: someone else may have just done this"

    Scenario: Where the prescription is comes before what is missing from the request
      Given a prescription that is AWAITING_PACKAGING
      When the pharmacist rejects it with the reason " "
      Then the staff member is told "Prescription #7 is waiting to be packed, so that step is not possible now"

    Scenario: A step that does not follow from where the prescription is
      Given a prescription that is READY_FOR_PICKUP
      When it is handed over
      Then the staff member is told "Prescription #7 is ready for pickup, so that step is not possible now"


    Scenario Outline: While <status>: <step>
      Given a prescription that is <status>
      When <step>
      Then that is not allowed

      Examples:
        | status             | step                        |
        | RECEIVED           | the pharmacist approves it  |
        | AWAITING_APPROVAL  | it is packed                |
        | AWAITING_PACKAGING | the pharmacist approves it  |
        | READY_FOR_PICKUP   | it is handed over           |
        | READY_FOR_PICKUP   | the patient does not answer |
        | OUT_OF_STOCK       | the stock is reserved       |

  Rule: COLLECTED, OUT_OF_STOCK, REJECTED and NO_SHOW are final

    Scenario Outline: <status> leads nowhere
      Given a prescription that is <status>
      Then it is final
      And it cannot move to any other status

      Examples:
        | status       |
        | COLLECTED    |
        | OUT_OF_STOCK |
        | REJECTED     |
        | NO_SHOW      |

  Rule: Only these moves are possible

    Scenario: Every other move between statuses is refused
      Then statuses can only move like this:
        | from               | to                 |
        | RECEIVED           | AWAITING_APPROVAL  |
        | RECEIVED           | OUT_OF_STOCK       |
        | AWAITING_APPROVAL  | AWAITING_PACKAGING |
        | AWAITING_APPROVAL  | REJECTED           |
        | AWAITING_PACKAGING | READY_FOR_PICKUP   |
        | READY_FOR_PICKUP   | CALLED             |
        | CALLED             | CALLED             |
        | CALLED             | COLLECTED          |
        | CALLED             | NO_SHOW            |
