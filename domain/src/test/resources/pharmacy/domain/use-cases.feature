Feature: Use cases
  The use cases against the ports in memory: who is served first, what goes back on the shelf, what the patient's
  page and the staff queues show, and what a check-in sent twice does.

  Background:
    Given the pharmacy has 3 packs of Atorvastatin 20 mg

  Rule: The earliest ticket gets the last pack, however the stock checks are ordered

    Scenario: A later ticket's stock check runs first
      Given Maria checked in for 1 packs of Atorvastatin 20 mg
      And Joao checked in for 3 packs of Atorvastatin 20 mg
      When Joao's stock check runs
      Then Maria's prescription is AWAITING_APPROVAL
      And Joao's prescription is OUT_OF_STOCK
      And the shelf has 2 packs of Atorvastatin 20 mg

    Scenario: The last pack goes to the earlier ticket
      Given Maria checked in for 2 packs of Atorvastatin 20 mg
      And Joao checked in for 2 packs of Atorvastatin 20 mg
      When Joao's stock check runs
      Then Maria's prescription is AWAITING_APPROVAL
      And Joao's prescription is OUT_OF_STOCK
      And the shelf has 1 packs of Atorvastatin 20 mg

    Scenario: A stock check serves the patients ahead of it, and nobody behind it
      Given Maria checked in for 1 packs of Atorvastatin 20 mg
      And Joao checked in for 1 packs of Atorvastatin 20 mg
      When Maria's stock check runs
      Then Maria's prescription is AWAITING_APPROVAL
      And Joao's prescription is RECEIVED

    Scenario: A repeated message changes nothing
      Given Maria checked in for 2 packs of Atorvastatin 20 mg
      And Maria's stock check ran
      When a repeated message makes Maria's stock check run again
      Then Maria's prescription is AWAITING_APPROVAL
      And the shelf has 1 packs of Atorvastatin 20 mg

    Scenario: Staff can run a stock check by hand
      Given Maria checked in for 1 packs of Atorvastatin 20 mg
      When staff retry Maria's stock check
      Then Maria's prescription is AWAITING_APPROVAL

  Rule: A prescription is filled whole or not at all

    Scenario: What was reserved before the shortage is put back
      Given Maria checked in for:
        | medication         | packs |
        | Amoxicillin 500 mg | 2     |
        | Atorvastatin 20 mg | 4     |
      When Maria's stock check runs
      Then Maria's prescription is OUT_OF_STOCK
      And the shelf has 10 packs of Amoxicillin 500 mg
      And the shelf has 3 packs of Atorvastatin 20 mg

  Rule: Packs go back on the shelf when the patient does not get them

    Scenario: The pharmacist rejects a prescription
      Given Maria checked in for 2 packs of Atorvastatin 20 mg
      And Maria's stock check ran
      When the pharmacist rejects Maria's prescription with the reason "Allergy on file"
      Then Maria's prescription is REJECTED
      And the shelf has 3 packs of Atorvastatin 20 mg

    Scenario: A rejection that is turned down puts nothing back
      Given Maria checked in for 2 packs of Atorvastatin 20 mg
      And Maria's stock check ran
      When the pharmacist rejects Maria's prescription with the reason " "
      Then Maria's prescription is AWAITING_APPROVAL
      And the shelf has 1 packs of Atorvastatin 20 mg

    Scenario: Nobody answers when the number is called
      Given Maria checked in for 2 packs of Atorvastatin 20 mg
      And Maria's stock check ran
      And Maria's prescription was approved, packed and called
      When Maria does not answer when called
      Then Maria's prescription is NO_SHOW
      And the shelf has 3 packs of Atorvastatin 20 mg

    Scenario: A patient turned away for lack of stock gets the packs of a no show by checking in again
      Given Maria checked in for 3 packs of Atorvastatin 20 mg
      And Maria's stock check ran
      And Joao checked in for 2 packs of Atorvastatin 20 mg
      And Joao's stock check ran
      And Maria's prescription was approved, packed and called
      Then Joao's prescription is OUT_OF_STOCK
      When Maria does not answer when called
      And Joao checks in again for 2 packs of Atorvastatin 20 mg
      And Joao's stock check runs
      Then Joao's prescription is AWAITING_APPROVAL
      And the shelf has 1 packs of Atorvastatin 20 mg

    Scenario: A number that was never called cannot be a no show, and keeps its packs
      Given Maria checked in for 2 packs of Atorvastatin 20 mg
      And Maria's stock check ran
      And Maria's prescription was approved and packed
      When Maria does not answer when called
      Then the staff member is turned away with "Prescription #1 is ready for pickup, so that step is not possible now"
      And the shelf has 1 packs of Atorvastatin 20 mg

    Scenario: Packs of a prescription that was collected stay out
      Given Maria checked in for 2 packs of Atorvastatin 20 mg
      And Maria's stock check ran
      And Maria's prescription was approved, packed and called
      When Maria's prescription is handed over
      And Maria does not answer when called
      Then the staff member is turned away with "Prescription #1 is collected, so that step is not possible now"
      And the shelf has 1 packs of Atorvastatin 20 mg

  Rule: A check-in sent again is the same check-in

    Scenario: The response was lost and the patient taps again
      Given Maria checked in with the id first for 2 packs of Atorvastatin 20 mg
      When Maria sends the same check-in again
      Then Maria gets the same ticket as before
      And only one check-in was recorded

    Scenario: The same id for other details is turned down
      Given Maria checked in with the id first for 2 packs of Atorvastatin 20 mg
      When Maria checks in with the id first for 1 packs of Atorvastatin 20 mg
      Then Maria is told "This check-in was already sent with other details. Please start a new check-in."
      And only one check-in was recorded

    Scenario: The same id for another patient is turned down
      Given Maria checked in with the id first for 2 packs of Atorvastatin 20 mg
      When Joao checks in with the id first for 2 packs of Atorvastatin 20 mg
      Then Joao is told "This check-in was already sent with other details. Please start a new check-in."

    Scenario: A medication the pharmacy does not know
      When Maria checks in with the id any for 1 packs of Paracetamol 500 mg
      Then Maria is told "Unknown medication 'paracetamol'"

  Rule: The patient's page says when the stock check is late, from the 30th second

    Scenario Outline: <seconds> seconds after check-in
      Given Maria checked in for 1 packs of Atorvastatin 20 mg
      When <seconds> seconds pass
      Then Maria's page <says> the stock check is taking longer than usual

      Examples:
        | seconds | says         |
        | 0       | does not say |
        | 29      | does not say |
        | 30      | says         |
        | 31      | says         |

    Scenario: Once the stock is checked nothing is late any more
      Given Maria checked in for 1 packs of Atorvastatin 20 mg
      And Maria's stock check ran
      When 120 seconds pass
      Then Maria's page does not say the stock check is taking longer than usual

  Rule: Staff and the waiting room see tickets in the order they were drawn

    Scenario: The queues
      Given Joao checked in for 1 packs of Atorvastatin 20 mg
      And Maria checked in for 1 packs of Atorvastatin 20 mg
      And Joao's stock check ran
      And Maria's stock check ran
      Then the pharmacist queue lists "Joao, Maria"

    Scenario: The board
      Given Ana checked in for 1 packs of Atorvastatin 20 mg
      And Bia checked in for 1 packs of Ibuprofen 400 mg
      And Cris checked in for 1 packs of Amoxicillin 500 mg
      And Dani checked in for 1 packs of Metformin 850 mg
      And Ana's stock check ran
      And Bia's stock check ran
      And Cris's stock check ran
      And Bia's prescription was approved and packed
      And Cris's prescription was approved, packed and called
      Then the board shows "3" being called, "2" ready and "1, 4" being prepared
      And the pickup queue lists "Bia, Cris"

    Scenario: A finished prescription leaves the board
      Given Ana checked in for 1 packs of Atorvastatin 20 mg
      And Ana's stock check ran
      And Ana's prescription was approved, packed and called
      When Ana's prescription is handed over
      Then the board shows "" being called, "" ready and "" being prepared

  Rule: A stock top-up is between 1 and 1000 packs, of a medication that exists

    Scenario Outline: Topping up by <packs> packs
      When the stock of Atorvastatin 20 mg is topped up by <packs> packs
      Then <result>

      Examples:
        | packs | result                                                                |
        | 1     | the shelf has 4 packs of Atorvastatin 20 mg                           |
        | 1000  | the shelf has 1003 packs of Atorvastatin 20 mg                        |
        | 0     | the restock is refused with "Restock between 1 and 1000 packs"        |
        | 1001  | the restock is refused with "Restock between 1 and 1000 packs"        |

    Scenario: A medication that is not in the catalog
      When the stock of nope is topped up by 5 packs
      Then the restock is refused with "Medication nope not found"

  Rule: Two tickets on one government prescription cannot both take the same packs

    Background:
      Given the government prescription GOV-1001 lists 2 packs of Ibuprofen 400 mg as a maximum

    Scenario: The second phone is told what is left
      Given Maria checked in on it for 2 packs of Ibuprofen 400 mg
      When Joao checks in on it for 1 packs of Ibuprofen 400 mg
      Then Joao is told "Government prescription GOV-1001 has no Ibuprofen 400 mg left to collect (2 packs of it already on a ticket that is still in progress)"

    Scenario: What the first ticket left is still there
      Given Maria checked in on it for 1 packs of Ibuprofen 400 mg
      When Joao checks in on it for 1 packs of Ibuprofen 400 mg
      Then Joao's ticket number is 2

    Scenario: A ticket that ended without medication holds nothing
      Given Maria checked in on it for 2 packs of Ibuprofen 400 mg
      And Maria's stock check ran
      And the pharmacist rejects Maria's prescription with the reason "Allergy on file"
      When Joao checks in on it for 2 packs of Ibuprofen 400 mg
      Then Joao's ticket number is 2

    Scenario: Once the government system knows of what was taken, it is counted once
      Given Maria checked in on it for 1 packs of Ibuprofen 400 mg
      And the government system has been told what Maria took
      When Joao checks in on it for 1 packs of Ibuprofen 400 mg
      Then Joao's ticket number is 2

    Scenario: A double tap is one check-in: the second is not judged against the packs of the first
      When Maria checks in on it with the id first for 2 packs of Ibuprofen 400 mg, and a double tap sends the same check-in while it waits for its ticket number
      Then Maria's check-in is thrown back to be run again, not refused
      When Maria sends the same check-in again
      Then Maria gets the same ticket as before
      And only one check-in was recorded

    Scenario: A hand-over queues one commit, however often it is pressed
      Given Maria checked in on it for 2 packs of Ibuprofen 400 mg
      And Maria's stock check ran
      And Maria's prescription was approved, packed and called
      When Maria's prescription is handed over
      And Maria's prescription is handed over
      Then the staff member is turned away with "Prescription #1 is already collected: someone else may have just done this"
      And 1 commit to the government system is queued

  Rule: A government prescription number is looked up as typed, minus the spaces

    Background:
      Given the government system knows GOV-1001 with 2 packs of Ibuprofen 400 mg left, and GOV-1004 used in full

    Scenario: A number typed in lower case, with spaces
      When a patient looks up "  gov-1001 "
      Then they see government prescription GOV-1001 with 2 packs of Ibuprofen 400 mg to collect

    Scenario Outline: Numbers that do not work: <typed>
      When a patient looks up "<typed>"
      Then the patient is told "<message>"

      Examples:
        | typed                                       | message                                                                     |
        |                                             | Please enter the number of your government prescription                      |
        | NOPE                                        | We could not find a government prescription with number NOPE. Please check the number. |
        | GOV-1004                                    | Government prescription GOV-1004 has already been used in full               |
        | 12345678901234567890123456789012345678901   | A government prescription number has at most 40 characters: please check yours |

    Scenario: The government system does not answer
      Given the government system does not answer
      When a patient looks up "GOV-1001"
      Then the patient is told "We can't reach the government prescription system right now. Please try again in a moment, or enter your medication by hand."
